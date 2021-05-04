package org.apache.flink.kubernetes.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.flink.kubernetes.operator.crd.spec.FlinkApplicationSpec;
import org.apache.flink.kubernetes.operator.crd.status.FlinkApplicationStatus;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize()
public class FlinkApplication extends CustomResource {
    private FlinkApplicationSpec spec;
    private FlinkApplicationStatus status;

    public FlinkApplicationSpec getSpec() {
        return spec;
    }

    public void setSpec(FlinkApplicationSpec spec) {
        this.spec = spec;
    }

    public FlinkApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(FlinkApplicationStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "FlinkApplication{"+
                "apiVersion='" + getApiVersion() + "'" +
                ", metadata=" + getMetadata() +
                ", spec=" + spec +
                ", status=" + status +
                "}";
    }

    @Override
    public ObjectMeta getMetadata() {
        return super.getMetadata();
    }
}
