package org.example

class BlueGreenDeployment implements Serializable {

    private def script

    BlueGreenDeployment(def script) {
        this.script = script
    }

    void deploy(String service, String version, String environment) {
        script.echo "=== BLUE-GREEN DEPLOY: ${service} v${version} on ${environment} ==="

        String liveSlot  = getActiveSlot(service, environment)
        String idleSlot  = (liveSlot == 'blue') ? 'green' : 'blue'

        script.echo "Live: ${liveSlot}  →  Deploying to idle: ${idleSlot}"

        script.sh """
            kubectl set image deployment/${service}-${idleSlot} \
                ${service}=ghcr.io/opstree/${service}:${version} \
                --namespace=${environment}
        """

        script.sh """
            kubectl rollout status deployment/${service}-${idleSlot} \
                --namespace=${environment} --timeout=5m
        """

        script.sh """
            IDLE_IP=\$(kubectl get svc ${service}-${idleSlot} \
                -n ${environment} \
                -o jsonpath='{.spec.clusterIP}')
            curl -sf http://\$IDLE_IP/health || exit 1
        """

        script.sh """
            kubectl patch service ${service} \
                -n ${environment} \
                -p '{"spec":{"selector":{"slot":"${idleSlot}"}}}'
        """

        script.echo "${idleSlot} is now LIVE for ${service}"
    }

    void rollback(String service, String environment) {
        script.echo "ROLLBACK: switching ${service} back to previous slot"
        String current  = getActiveSlot(service, environment)
        String previous = (current == 'blue') ? 'green' : 'blue'

        script.sh """
            kubectl patch service ${service} \
                -n ${environment} \
                -p '{"spec":{"selector":{"slot":"${previous}"}}}'
        """
        script.echo "Rollback done. ${previous} is LIVE again."
    }

    private String getActiveSlot(String service, String environment) {
        return script.sh(
            script: "kubectl get svc ${service} -n ${environment} -o jsonpath='{.spec.selector.slot}'",
            returnStdout: true
        ).trim() ?: 'blue'
    }
}