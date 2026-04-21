package org.example

class CanaryDeployment implements Serializable {

    private def script

    CanaryDeployment(def script) {
        this.script = script
    }

    void deploy(String service, String version, String environment) {
        script.echo "=== CANARY DEPLOY: ${service} v${version} on ${environment} ==="

        // deploy canary pods with local image
        script.sh """
            kubectl set image deployment/${service}-canary \
                ${service}=${service}:${version} \
                --namespace=${environment}
        """
        script.sh """
            kubectl rollout status deployment/${service}-canary \
                --namespace=${environment} --timeout=5m
        """

        // promote canary to stable
        script.sh """
            kubectl set image deployment/${service} \
                ${service}=${service}:${version} \
                --namespace=${environment}
            kubectl rollout status deployment/${service} \
                --namespace=${environment} --timeout=5m
            kubectl scale deployment/${service}-canary \
                --replicas=0 --namespace=${environment}
        """

        script.echo "Canary promoted. ${service} v${version} fully live."
    }

    void rollback(String service, String environment) {
        script.echo "CANARY ROLLBACK: rolling back to previous version"
        script.sh """
            kubectl rollout undo deployment/${service}-canary \
                --namespace=${environment}
            kubectl scale deployment/${service}-canary \
                --replicas=0 --namespace=${environment}
        """
        script.echo "Canary rollback complete."
    }
}