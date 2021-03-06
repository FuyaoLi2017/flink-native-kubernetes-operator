package org.apache.flink.kubernetes.operator.controller;

import java.util.concurrent.atomic.AtomicReference;
import org.apache.flink.kubernetes.operator.Utils.Constants;
import org.apache.flink.kubernetes.operator.Utils.FlinkUtils;
import org.apache.flink.kubernetes.operator.Utils.KubernetesUtils;
import org.apache.flink.kubernetes.operator.crd.DoneableFlinkApplication;
import org.apache.flink.kubernetes.operator.crd.FlinkApplication;
import org.apache.flink.kubernetes.operator.crd.FlinkApplicationList;
import org.apache.flink.kubernetes.operator.crd.status.FlinkApplicationStatus;
import org.apache.flink.kubernetes.operator.crd.status.JobStatus;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.HTTPIngressRuleValueBuilder;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.api.model.extensions.IngressBuilder;
import io.fabric8.kubernetes.api.model.extensions.IngressRule;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.informers.cache.Lister;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.client.cli.ApplicationDeployer;
import org.apache.flink.client.deployment.ClusterClientServiceLoader;
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader;
import org.apache.flink.client.deployment.application.ApplicationConfiguration;
import org.apache.flink.client.deployment.application.cli.ApplicationClusterDeployer;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.runtime.client.JobStatusMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FlinkApplicationController {
    // https://developers.redhat.com/blog/2019/10/07/write-a-simple-kubernetes-operator-in-java-using-the-fabric8-kubernetes-client/
    private static final Logger LOG = LoggerFactory.getLogger(FlinkApplicationController.class);
    private static final int RECONCILE_INTERVAL_MS = 60 * 1000;

    private final KubernetesClient kubernetesClient;
    private final MixedOperation<FlinkApplication, FlinkApplicationList, DoneableFlinkApplication, Resource<FlinkApplication, DoneableFlinkApplication>> flinkAppK8sClient;
    private final SharedIndexInformer<FlinkApplication> flinkAppInformer;
    private final Lister<FlinkApplication> flinkClusterLister;

    private final BlockingQueue<String> workqueue;
    private final Map<String, Tuple2<FlinkApplication, Configuration>> flinkApps;
    private final Map<String, String> savepointLocation;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final String operatorNamespace;

    public FlinkApplicationController(
            KubernetesClient kubernetesClient,
            MixedOperation<FlinkApplication, FlinkApplicationList, DoneableFlinkApplication, Resource<FlinkApplication, DoneableFlinkApplication>> flinkAppK8sClient,
            SharedIndexInformer<FlinkApplication> flinkAppInformer,
            String namespace) {
        this.kubernetesClient = kubernetesClient;
        this.flinkAppK8sClient = flinkAppK8sClient;
        this.flinkClusterLister = new Lister<>(flinkAppInformer.getIndexer(), namespace);
        this.flinkAppInformer = flinkAppInformer;
        this.operatorNamespace = namespace;

        this.workqueue = new ArrayBlockingQueue<>(1024);
        this.flinkApps = new ConcurrentHashMap<>();
        this.savepointLocation = new HashMap<>();
    }

    public void create() {
        flinkAppInformer.addEventHandler(new ResourceEventHandler<FlinkApplication>() {
            @Override
            public void onAdd(FlinkApplication flinkApplication) {
                addToWorkQueue(flinkApplication);
            }

            @Override
            public void onUpdate(FlinkApplication flinkApplication, FlinkApplication newFlinkApplication) {
                addToWorkQueue(newFlinkApplication);
            }

            @Override
            public void onDelete(FlinkApplication flinkApplication, boolean b) {
                final String clusterId = flinkApplication.getMetadata().getName();
                final String namespace = flinkApplication.getMetadata().getNamespace();
                LOG.info("{} is deleted, destroying flink resources", clusterId);
                kubernetesClient
                    .apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName(clusterId)
                    .cascading(true)
                    .delete();
                flinkApps.remove(clusterId);
            }
        });
    }

    public void run() {
        LOG.info("Starting FlinkApplication controller");
        executorService.submit(new JobStatusUpdater());

        while (true) {
            if (!flinkAppInformer.hasSynced()) {
                continue;
            }
            try {
                LOG.info("Trying to get item from work queue");
                if (workqueue.isEmpty()) {
                    LOG.info("Work queue is empty");
                }
                String item = workqueue.take();
                if (item.isEmpty() || (!item.contains("/"))) {
                    LOG.warn("Ignoring invalid resource item: {}", item);
                }

                // Get the FlinkApplication resource's name from key which is in format namespace/name
                String name = item.split("/")[1];
                FlinkApplication flinkApplication = flinkClusterLister.get(item.split("/")[1]);
                if (flinkApplication == null) {
                    LOG.error("FlinkApplication {} in work queue no longer exists", name);
                    continue;
                }
                LOG.info("Reconciling " + flinkApplication);
                reconcile(flinkApplication);

            } catch (InterruptedException interruptedException) {
                LOG.error("Controller interrupted");
            }
        }
    }

    /**
     * Tries to achieve the desired state for flink cluster.
     *
     * @param flinkApp specified flink cluster
     */
    private void reconcile(FlinkApplication flinkApp) {
        final String namespace = flinkApp.getMetadata().getNamespace();
        final String clusterId = flinkApp.getMetadata().getName();
        final Deployment deployment = kubernetesClient.apps().deployments().inNamespace(namespace).withName(clusterId).get();

        final Configuration effectiveConfig;
        try {
            effectiveConfig = FlinkUtils.getEffectiveConfig(namespace, clusterId, flinkApp.getSpec());
        } catch (Exception e) {
            LOG.error("Failed to load configuration", e);
            return;
        }

        // Create new Flink application
        if (!flinkApps.containsKey(clusterId) && deployment == null) {
            // Deploy application
            final ClusterClientServiceLoader clusterClientServiceLoader = new DefaultClusterClientServiceLoader();
            final ApplicationDeployer deployer = new ApplicationClusterDeployer(clusterClientServiceLoader);

            final ApplicationConfiguration applicationConfiguration =
                new ApplicationConfiguration(flinkApp.getSpec().getMainArgs(), flinkApp.getSpec().getEntryClass());
            try {
                deployer.run(effectiveConfig, applicationConfiguration);
            } catch (Exception e) {
                LOG.error("Failed to deploy cluster {}", clusterId, e);
            }

            flinkApps.put(clusterId, new Tuple2<>(flinkApp, effectiveConfig));

            updateIngress();
        } else {
            if (!flinkApps.containsKey(clusterId)) {
                LOG.info("Recovering {}", clusterId);
                flinkApps.put(clusterId, new Tuple2<>(flinkApp, effectiveConfig));
                return;
            }
            // Flink app is deleted externally
            if (deployment == null) {
                LOG.warn("{} is delete externally.", clusterId);
                flinkApps.remove(clusterId);
                return;
            }

            FlinkApplication oldFlinkApp = flinkApps.get(clusterId).f0;

            // Trigger a new savepoint
            triggerSavepoint(oldFlinkApp, flinkApp, effectiveConfig);

            // TODO support more fields updating, e.g. image, resources
            // Task 1: support dual mode: image updating
            triggerImageUpdate(oldFlinkApp, flinkApp, effectiveConfig);
        }
    }

    private void updateIngress() {
        final List<IngressRule> ingressRules = new ArrayList<>();
        for (Tuple2<FlinkApplication, Configuration> entry : flinkApps.values()) {
            final FlinkApplication flinkApp = entry.f0;
            final String clusterId = flinkApp.getMetadata().getName();
            final int restPort = entry.f1.getInteger(RestOptions.PORT);

            final String ingressHost = clusterId + Constants.INGRESS_SUFFIX;
            ingressRules.add(new IngressRule(ingressHost, new HTTPIngressRuleValueBuilder()
                .addNewPath()
                .withNewBackend().withNewServiceName(clusterId + Constants.REST_SVC_NAME_SUFFIX).withNewServicePort(restPort).endBackend()
                .endPath()
                .build()));
        }
        final Ingress ingress = new IngressBuilder()
            .withApiVersion(Constants.INGRESS_API_VERSION)
            .withNewMetadata().withName(Constants.FLINK_NATIVE_K8S_OPERATOR_NAME).endMetadata()
            .withNewSpec()
            .withRules(ingressRules)
            .endSpec()
            .build();
        // Get operator deploy
        final Deployment deployment = kubernetesClient.apps().deployments().inNamespace(operatorNamespace).withName(
            Constants.FLINK_NATIVE_K8S_OPERATOR_NAME).get();
        if (deployment == null) {
            LOG.warn("Could not find deployment {}", Constants.FLINK_NATIVE_K8S_OPERATOR_NAME);
        } else {
            KubernetesUtils.setOwnerReference(deployment, Collections.singletonList(ingress));
        }
        kubernetesClient.resourceList(ingress).inNamespace(operatorNamespace).createOrReplace();
    }

    private void triggerSavepoint(FlinkApplication oldFlinkApp, FlinkApplication newFlinkApp, Configuration effectiveConfig) {
        final int generation = newFlinkApp.getSpec().getSavepointGeneration();
        if (generation > oldFlinkApp.getSpec().getSavepointGeneration()) {
            try {
                ClusterClient<String> clusterClient = FlinkUtils.getRestClusterClient(effectiveConfig);
                final CompletableFuture<Collection<JobStatusMessage>> jobDetailsFuture = clusterClient.listJobs();
                jobDetailsFuture.get().forEach(
                    status -> {
                        LOG.debug("JobStatus for cluster ID: {} : {}", clusterClient.getClusterId(), status.getJobState());
                        clusterClient.triggerSavepoint(status.getJobId(), null)
                            .thenAccept(path -> {
                                savepointLocation.put(status.getJobId().toString(), path);
                                LOG.info("Trigger a Savepoint: savepoint generated Successfully, path: {}", path);
                            });
                    });
            } catch (Exception e) {
                LOG.warn("Failed to trigger a new savepoint with generation {}", generation);
            }
        }
    }

    // dual mode in Lyft operator
    private void triggerImageUpdate(FlinkApplication oldFlinkApp, FlinkApplication newFlinkApp, Configuration effectiveConfig) {
        final String oldImageName = oldFlinkApp.getSpec().getImageName();
        final String newImageName = newFlinkApp.getSpec().getImageName();
////        -d,--drain  Send MAX_WATERMARK before taking the savepoint and stopping the pipelne. default to false
//        final boolean advanceToEndOfEventTime = newFlinkApp.getSpec().isDrainFlag();
        LOG.info("Trying to compare image! old Image: {}, new image: {}.", oldImageName, newImageName);


        if (!oldImageName.equals(newImageName)) {
            LOG.info("Image need to be updated! old Image: {}, new image: {}.", oldImageName, newImageName);
            final String namespace = newFlinkApp.getMetadata().getNamespace();
            final String clusterId = newFlinkApp.getMetadata().getName();

            final Configuration newEffectiveConfig;

            try {
                newEffectiveConfig = FlinkUtils.getEffectiveConfig(namespace, clusterId, newFlinkApp.getSpec());
            } catch (Exception e) {
                LOG.error("Failed to load configuration", e);
                return;
            }

            try {
                ClusterClient<String> clusterClient = FlinkUtils.getRestClusterClient(effectiveConfig);
                final CompletableFuture<Collection<JobStatusMessage>> jobDetailsFuture = clusterClient.listJobs();

                // Solution 1:
                jobDetailsFuture.get().forEach(
                    status -> {
                        // step 1: stop the previous job with a savepoint
                        LOG.info("JobStatus for cluster ID: {} : {}", clusterClient.getClusterId(), status.getJobState());
                        LOG.info("Going to trigger a cancel with savepoint action with job id: {}", status.getJobId());

                        CompletableFuture<Void> cancelWithSavepointFuture = clusterClient.cancelWithSavepoint(status.getJobId(), null)
                            .thenAccept(path ->{
                                savepointLocation.put(status.getJobId().toString(), path);
                                LOG.info("Cancel with Savepoint: savepoint generated Successfully, path: {}", path);
                                newFlinkApp.getSpec().setFromSavepoint(path);
                            });

                        cancelWithSavepointFuture.join();


                        final String oldClusterId = oldFlinkApp.getMetadata().getName();
                        final String oldNamespace = oldFlinkApp.getMetadata().getNamespace();

                        // Wait for cancelWithSavepoint() to finish deleting the previous flink application.
                        int count = 1;
                        while (true) {
                            try {
                                Thread.sleep(3000);
                                Deployment currentDeployment = kubernetesClient.apps().deployments().inNamespace(oldNamespace).withName(oldClusterId).get();
                                if (currentDeployment == null) {
                                    LOG.info("Old deployment is completed Terminated. Is ready for new deployment.");
                                    break;
                                }
                                LOG.info("Wait for deployment to stop. wait time: {} seconds.", count * 3);
                                LOG.info("The old deployment {} is existing, already triggered a delete operation, old deployment status: {}",oldClusterId,  currentDeployment.getStatus().toString());
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                        flinkApps.remove(oldClusterId);

                        // deploy new flink application
                        if (newFlinkApp.getSpec().getFromSavepoint() != null) {
                            final ClusterClientServiceLoader clusterClientServiceLoader = new DefaultClusterClientServiceLoader();
                            final ApplicationDeployer deployer = new ApplicationClusterDeployer(clusterClientServiceLoader);

                            final ApplicationConfiguration applicationConfiguration =
                                new ApplicationConfiguration(newFlinkApp.getSpec().getMainArgs(), newFlinkApp.getSpec().getEntryClass());
                            try {
                                LOG.info("Trying to deploy a new application with new image name: {}", newImageName);
                                deployer.run(effectiveConfig, applicationConfiguration);
                                flinkApps.put(newFlinkApp.getMetadata().getName(), new Tuple2<>(newFlinkApp, newEffectiveConfig));

                                updateIngress();
                            } catch (Exception e) {
                                LOG.error("Failed to deploy image updated cluster {}", newFlinkApp.getMetadata().getName(), e);
                            }
                        }
                        else {
                            LOG.warn("The savepoint path is not set into the new application!, fromSavepoint value: {}", newFlinkApp.getSpec().getFromSavepoint());
                        }
                    });

            } catch (Exception e) {
                LOG.warn("Failed to trigger a image update action, oldImageName: {}, newImageName: {}. Exception: {}", oldImageName, newImageName, e);
            }
        }
    }

    private void addToWorkQueue(FlinkApplication flinkApplication) {
        String item = Cache.metaNamespaceKeyFunc(flinkApplication);
        if (item != null && !item.isEmpty()) {
            LOG.info("Adding item {} to work queue", item);
            workqueue.add(item);
        }
    }

    private class JobStatusUpdater implements Runnable {
        @Override
        public void run() {
            LOG.info("Starting JobStatusUpdater");
            while (true) {
                for (Tuple2<FlinkApplication, Configuration> flinkApp : flinkApps.values()) {
                    try {
                        final ClusterClient<String> clusterClient = FlinkUtils.getRestClusterClient(flinkApp.f1);
                        final CompletableFuture<Collection<JobStatusMessage>> jobDetailsFuture = clusterClient.listJobs();
                        final List<JobStatus> jobStatusList = new ArrayList<>();
                        jobDetailsFuture.get().forEach(
                            status -> {
                                LOG.debug("JobStatus for cluster ID: {} : {}", clusterClient.getClusterId(), status.getJobState());
                                final String jobId = status.getJobId().toString();
                                final JobStatus jobStatus = new JobStatus(
                                    status.getJobName(),
                                    jobId,
                                    status.getJobState().name(),
                                    String.valueOf(System.currentTimeMillis()));
                                if (savepointLocation.containsKey(jobId)) {
                                    jobStatus.setSavepointLocation(savepointLocation.get(jobId));
                                }
                                jobStatusList.add(jobStatus);
                            });
                        flinkApp.f0.setStatus(new FlinkApplicationStatus(jobStatusList.toArray(new JobStatus[0])));

                        // TODO: FUYAO - support the updateImage Use case
                        flinkAppK8sClient.inNamespace(flinkApp.f0.getMetadata().getNamespace()).createOrReplace(flinkApp.f0);
                    } catch (Exception e) {
                        flinkApp.f0.setStatus(new FlinkApplicationStatus());
                        LOG.warn("Failed to list jobs for {}", flinkApp.f0.getMetadata().getName(), e);
                    }
                }

                try {
                    Thread.sleep(RECONCILE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    LOG.error("JobStatusUpdater interrupt");
                }
            }
        }
    }
}
