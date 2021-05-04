package org.apache.flink.kubernetes.operator.crd.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import java.util.List;
import lombok.ToString;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize()
@ToString
public class FlinkApplicationSpec implements KubernetesResource {
    private String imageName;
    private String imagePullPolicy;
    private List<String> imagePullSecrets;

    private String jarURI;
    private String[] mainArgs = new String[0];
    private String entryClass;

    private int parallelism;

    private Resource jobManagerResource;
    private Resource taskManagerResource;

    private String fromSavepoint;
    private boolean allowNonRestoredState = false;
    private String savepointsDir;
    private int savepointGeneration;

    private boolean drainFlag = false;

    private Map<String, String> flinkConfig;

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getImagePullPolicy() {
        return imagePullPolicy;
    }

    public void setImagePullPolicy(String imagePullPolicy) {
        this.imagePullPolicy = imagePullPolicy;
    }

    public List<String> getImagePullSecrets() {
        return imagePullSecrets;
    }

    public void setImagePullSecrets(List<String> imagePullSecrets) {
        this.imagePullSecrets = imagePullSecrets;
    }

    public String getJarURI() {
        return jarURI;
    }

    public void setJarURI(String jarURI) {
        this.jarURI = jarURI;
    }

    public String[] getMainArgs() {
        return mainArgs;
    }

    public void setMainArgs(String[] mainArgs) {
        this.mainArgs = mainArgs;
    }

    public String getEntryClass() {
        return entryClass;
    }

    public void setEntryClass(String entryClass) {
        this.entryClass = entryClass;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public Resource getJobManagerResource() {
        return jobManagerResource;
    }

    public void setJobManagerResource(Resource jobManagerResource) {
        this.jobManagerResource = jobManagerResource;
    }

    public Resource getTaskManagerResource() {
        return taskManagerResource;
    }

    public void setTaskManagerResource(Resource taskManagerResource) {
        this.taskManagerResource = taskManagerResource;
    }

    public Map<String, String> getFlinkConfig() {
        return flinkConfig;
    }

    public void setFlinkConfig(Map<String, String> flinkConfig) {
        this.flinkConfig = flinkConfig;
    }

    public String getFromSavepoint() {
        return fromSavepoint;
    }

    public void setFromSavepoint(String fromSavepoint) {
        this.fromSavepoint = fromSavepoint;
    }

    public boolean isAllowNonRestoredState() {
        return allowNonRestoredState;
    }

    public void setAllowNonRestoredState(boolean allowNonRestoredState) {
        this.allowNonRestoredState = allowNonRestoredState;
    }

    public String getSavepointsDir() {
        return savepointsDir;
    }

    public void setSavepointsDir(String savepointsDir) {
        this.savepointsDir = savepointsDir;
    }

    public int getSavepointGeneration() {
        return savepointGeneration;
    }

    public void setSavepointGeneration(int savepointGeneration) {
        this.savepointGeneration = savepointGeneration;
    }

    public boolean isDrainFlag() {
        return drainFlag;
    }

    public void setDrainFlag(boolean drainFlag) {
        this.drainFlag = drainFlag;
    }
}
