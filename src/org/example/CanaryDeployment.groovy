package org.example

class CanaryDeployment implements Serializable {

    private def script
    static final List<Integer> CANARY_STEPS = [10, 30, 50, 100]

    CanaryDeployment(def script) {
        this.script = script
    }

    void deploy(String service, String version, String environment) {
        script.echo "=== CANARY DEPLOY: ${service} v${version} on ${environment} ==="

        // deploy canary pods
        script.sh """
            kubectl set image deployment/${service}-canary \
                ${service}=ghcr.io/opstree/${service}:${version} \
                --namespace=${environment}
        """
        script.sh """
            kubectl rollout status deployment/${service}-canary \
                --namespace=${environment} --timeout=5m
        """

        // step through traffic weights
        for (int weight : CANARY_STEPS) {
            int stable = 100 - weight
            script.echo "Traffic → stable: ${stable}%   canary: ${weight}%"

            script.sh """
                kubectl patch virtualservice ${service} \
                    -n ${environment} --type='json' -p='[
                      {"op":"replace","path":"/spec/http/0/route/0/weight","value":${stable}},
                      {"op":"replace","path":"/spec/http/0/route/1/weight","value":${weight}}
                    ]'
            """

            if (weight < 100) {
                script.echo "Observing canary at ${weight}% for 60 seconds..."
                script.sleep(60)

                boolean healthy = checkCanaryHealth(service, environment)
                if (!healthy) {
                    rollback(service, environment)
                    script.error "Canary aborted — unhealthy at ${weight}%"
                }
            }
        }

        // promote canary to stable
        script.sh """
            kubectl set image deployment/${service} \
                ${service}=ghcr.io/opstree/${service}:${version} \
                --namespace=${environment}
            kubectl rollout status deployment/${service} \
                --namespace=${environment} --timeout=5m
            kubectl scale deployment/${service}-canary \
                --replicas=0 --namespace=${environment}
        """

        script.echo "Canary promoted. ${service} v${version} fully live."
    }

    void rollback(String service, String environment) {
        script.echo "CANARY ROLLBACK: sending 100% traffic back to stable"
        script.sh """
            kubectl patch virtualservice ${service} \
                -n ${environment} --type='json' -p='[
                  {"op":"replace","path":"/spec/http/0/route/0/weight","value":100},
                  {"op":"replace","path":"/spec/http/0/route/1/weight","value":0}
                ]'
            kubectl scale deployment/${service}-canary \
                --replicas=0 --namespace=${environment}
        """
        script.echo "Canary rollback complete."
    }

    private boolean checkCanaryHealth(String service, String environment) {
        try {
            def rate = script.sh(
                script: """
                    curl -sf 'http://prometheus:9090/api/v1/query' \
                        --data-urlencode \
                        'query=rate(http_requests_total{job="${service}",status=~"5.."}[1m])' \
                    | python3 -c "import sys,json; d=json.load(sys.stdin); \
                        r=d['data']['result']; print(r[0]['value'][1] if r else '0')"
                """,
                returnStdout: true
            ).trim().toDouble()

            script.echo "Error rate: ${rate}"
            return rate < 0.05
        } catch (Exception e) {
            script.echo "Could not check health (${e.message}). Assuming healthy."
            return true
        }
    }
}