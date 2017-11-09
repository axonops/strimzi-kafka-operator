package io.enmasse.barnabas.controller.cluster.model;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.HashMap;
import java.util.Map;

public class Kafka {
    private final KubernetesClient client;
    private final String name;
    private final String namespace;
    private final String headlessName;

    private final int clientPort = 9092;
    private final String mounthPath = "/var/lib/kafka";
    private final String volumeName = "kafka-storage";

    private Map<String, String> labels = new HashMap<>();
    private int replicas = 3;
    private String image = "enmasseproject/kafka-statefulsets:latest";
    private String livenessProbeScript = "/opt/kafka/kafka_healthcheck.sh";
    private int livenessProbeTimeout = 5;
    private int livenessProbeInitialDelay = 15;
    private String readinessProbeScript = "/opt/kafka/kafka_healthcheck.sh";
    private int readinessProbeTimeout = 5;
    private int readinessProbeInitialDelay = 15;

    private Kafka(String name, String namespace, KubernetesClient client) {
        this.name = name;
        this.headlessName = name + "-headless";
        this.namespace = namespace;
        this.client = client;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public static Kafka fromConfigMap(ConfigMap cm, KubernetesClient client) {
        Kafka kafka = new Kafka(cm.getMetadata().getName(), cm.getMetadata().getNamespace(), client);
        kafka.setLabels(cm.getMetadata().getLabels());
        return kafka;
    }

    public static Kafka fromStatefulSet(StatefulSet ss, KubernetesClient client) {
        Kafka kafka =  new Kafka(ss.getMetadata().getName(), ss.getMetadata().getNamespace(), client);
        kafka.setLabels(ss.getMetadata().getLabels());
        return kafka;
    }

    private boolean statefulSetExists() {
        return client.apps().statefulSets().inNamespace(namespace).withName(name).get() == null ? false : true;
    }

    private boolean serviceExists() {
        return client.services().inNamespace(namespace).withName(name).get() == null ? false : true;
    }

    private boolean headlessServiceExists() {
        return client.services().inNamespace(namespace).withName(headlessName).get() == null ? false : true;
    }

    public void create() {
        createService();
        createHeadlessService();
        createStatefulSet();
    }

    private void createService() {
        Service svc = new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")
                .withSelector(new HashMap<String, String>(){{put("name", name);}})
                .addNewPort()
                .withPort(clientPort)
                .withNewTargetPort(clientPort)
                .withProtocol("TCP")
                .withName("kafka")
                .endPort()
                .endSpec()
                .build();
        client.services().inNamespace(namespace).createOrReplace(svc);
    }

    private void createHeadlessService() {
        Service svc = new ServiceBuilder()
                .withNewMetadata()
                .withName(headlessName)
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")
                .withClusterIP("None")
                .withSelector(new HashMap<String, String>(){{put("name", name);}})
                .addNewPort()
                .withPort(clientPort)
                .withNewTargetPort(clientPort)
                .withProtocol("TCP")
                .withName("kafka")
                .endPort()
                .endSpec()
                .build();
        client.services().inNamespace(namespace).createOrReplace(svc);
    }

    private void createStatefulSet() {
        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(image)
                .withVolumeMounts(new VolumeMountBuilder().withName(volumeName).withMountPath(mounthPath).build())
                .withPorts(new ContainerPortBuilder().withName("clientport").withProtocol("TCP").withContainerPort(clientPort).build())
                .withNewLivenessProbe()
                .withNewExec()
                .withCommand(livenessProbeScript)
                .endExec()
                .withInitialDelaySeconds(livenessProbeInitialDelay)
                .withTimeoutSeconds(livenessProbeTimeout)
                .endLivenessProbe()
                .withNewReadinessProbe()
                .withNewExec()
                .withCommand(readinessProbeScript)
                .endExec()
                .withInitialDelaySeconds(readinessProbeInitialDelay)
                .withTimeoutSeconds(readinessProbeTimeout)
                .endReadinessProbe()
                .build();
        Volume volume = new VolumeBuilder()
                .withName(volumeName)
                .withNewEmptyDir()
                .endEmptyDir()
                .build();
        StatefulSet statefulSet = new StatefulSetBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withServiceName(headlessName)
                .withReplicas(replicas)
                .withNewTemplate()
                .withNewMetadata()
                .withName(name)
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .withVolumes(volume)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        client.apps().statefulSets().inNamespace(namespace).createOrReplace(statefulSet);
    }

    public void delete() {
        deleteService();
        deleteStatefulSet();
        deleteHeadlessService();
    }

    private void deleteService() {
        if (serviceExists()) {
            client.services().inNamespace(namespace).withName(name).delete();
        }
    }

    private void deleteHeadlessService() {
        if (headlessServiceExists()) {
            client.services().inNamespace(namespace).withName(headlessName).delete();
        }
    }

    private void deleteStatefulSet() {
        if (statefulSetExists()) {
            client.apps().statefulSets().inNamespace(namespace).withName(name).delete();
        }
    }
}
