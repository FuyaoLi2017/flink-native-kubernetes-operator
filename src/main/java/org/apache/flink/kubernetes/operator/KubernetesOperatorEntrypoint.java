package org.apache.flink.kubernetes.operator;

import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import org.apache.flink.kubernetes.operator.crd.DoneableFlinkApplication;
import org.apache.flink.kubernetes.operator.crd.FlinkApplication;
import org.apache.flink.kubernetes.operator.crd.FlinkApplicationList;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import org.apache.flink.kubernetes.operator.controller.FlinkApplicationController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main Class for Flink native k8s operator.
 */
public class KubernetesOperatorEntrypoint {
	private static final Logger LOG = LoggerFactory.getLogger(KubernetesOperatorEntrypoint.class);

    public static void main(String args[]) {
        try (KubernetesClient k8sClient = new DefaultKubernetesClient()) {
            String namespace = k8sClient.getNamespace();
            if (namespace == null) {
                LOG.info("No namespace found via config, assuming default.");
                namespace = "default";
            }

            LOG.info("Using namespace : " + namespace);

	        final CustomResourceDefinition crdDefinition = new CustomResourceDefinitionBuilder()
		        .withNewMetadata().withName("flinkapplications.flink.k8s.io").endMetadata()
		        .withNewSpec()
		        .withGroup("flink.k8s.io")
		        .withVersion("v1alpha1")
		        .withNewNames().withKind("FlinkApplication").withPlural("flinkapplications").endNames()
		        .withScope("Namespaced")
		        .endSpec()
		        .build();

            final CustomResourceDefinitionContext crdContext = new CustomResourceDefinitionContext.Builder()
                    .withVersion("v1alpha1")
                    .withScope("Namespaced")
                    .withGroup("flink.k8s.io")
                    .withPlural("flinkapplications")
                    .build();

            final SharedInformerFactory informerFactory = k8sClient.informers();

            final SharedIndexInformer<FlinkApplication> flinkAppinformer = informerFactory.sharedIndexInformerForCustomResource(
            	crdContext,
	            FlinkApplication.class,
	            FlinkApplicationList.class,
	            10 * 60 * 1000);

//					SharedIndexInformer<FlinkApplication> flinkAppinformer2 = informerFactory.sharedIndexInformerFor(
//							FlinkApplication.class,
//							FlinkApplicationList.class,
//							new OperationContext().withNamespace("default"), // <- Namespace which you want to observe
//							30 * 1000L);

	        MixedOperation<FlinkApplication, FlinkApplicationList, DoneableFlinkApplication, Resource<FlinkApplication, DoneableFlinkApplication>> flinkAppK8sClient =
		        k8sClient.customResources(
		        	crdDefinition,
			        FlinkApplication.class,
			        FlinkApplicationList.class,
			        DoneableFlinkApplication.class);

            FlinkApplicationController flinkApplicationController = new FlinkApplicationController(
	            k8sClient,
	            flinkAppK8sClient,
	            flinkAppinformer,
	            namespace);

            flinkApplicationController.create();
            informerFactory.startAllRegisteredInformers();
            informerFactory.addSharedInformerEventListener(
            	exception -> LOG.error("Exception occurred, but caught", exception));

            flinkApplicationController.run();
        } catch (KubernetesClientException exception) {
            LOG.error("Kubernetes Client Exception : {}", exception);
        }
    }
}
