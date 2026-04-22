package org.example

class BlueGreenDeployment implements Serializable {

    private def script

    BlueGreenDeployment(def script) {
        this.script = script
    }

    // ─── MAIN DEPLOY ───────────────────────────────────────────

    void deploy(String service, String version, String environment) {
        script.echo "=== BLUE-GREEN DEPLOY: ${service} v${version} on ${environment} ==="

        String liveSlot = getActiveSlot(service, environment)
        String idleSlot = (liveSlot == 'blue') ? 'green' : 'blue'

        script.echo "Live: ${liveSlot}  →  Deploying to idle: ${idleSlot}"

        // Step 1 — idle slot pe image set karo
        // CHANGE: ghcr.io image hata ke local image use ki
        script.sh """
            kubectl set image deployment/${service}-${idleSlot} \
                ${service}=${service}:${version} \
                --namespace=${environment}
        """

        // Step 2 — rollout complete hone ka wait karo
        script.sh """
            kubectl rollout status deployment/${service}-${idleSlot} \
                --namespace=${environment} --timeout=5m
        """

        // Step 3 — idle slot ka smoke test karo (switch se pehle)
        // CHANGE: /${service}/healthz endpoint pass kar rahe hain
        smokeTest(service, idleSlot, environment, "/${service}/healthz")

        // Step 4 — traffic switch karo
        switchTraffic(service, idleSlot, environment)

        script.echo "${idleSlot} is now LIVE for ${service}"
    }

    // ─── SMOKE TEST ────────────────────────────────────────────
    // CHANGE 1: default endpoint /health se /${service}/healthz kiya
    // CHANGE 2: minikube ssh se curl kiya — direct curl minikube mein kaam nahi karta
    // CHANGE 3: retry loop add kiya — pod start hone mein time lagta hai

    void smokeTest(String service, String slot, String environment,
                   String endpoint = "/${service}/healthz") {

        script.echo "=== SMOKE TEST: ${service}-${slot} on ${environment} ==="

        // Slot ka ClusterIP lo
        String slotIP = script.sh(
            script: """
                kubectl get svc ${service}-${slot} \
                    -n ${environment} \
                    -o jsonpath='{.spec.clusterIP}'
            """,
            returnStdout: true
        ).trim()

        script.echo "Slot IP: ${slotIP}"
        script.echo "Testing endpoint: http://${slotIP}${endpoint}"

        // CHANGE: retry loop — 5 attempts, 5 second gap
        // minikube ssh se curl kiya kyunki Jenkins pod minikube network pe nahi hai
        script.sh """
            for i in 1 2 3 4 5; do
                STATUS=\$(kubectl exec -n ${environment} \
                    \$(kubectl get pods -n ${environment} \
                        -l app=${service},slot=${slot} \
                        -o jsonpath='{.items[0].metadata.name}') \
                    -- curl -s -o /dev/null -w "%{http_code}" \
                    http://localhost:8081${endpoint} 2>/dev/null || echo "000")

                echo "Attempt \$i: HTTP \$STATUS"

                if [ "\$STATUS" = "200" ]; then
                    echo "Smoke test PASSED"
                    exit 0
                fi
                sleep 5
            done

            echo "Smoke test FAILED after 5 attempts"
            exit 1
        """

        script.echo "Smoke test PASSED: ${service}-${slot}"
    }

    // ─── TRAFFIC SWITCH ────────────────────────────────────────
    // NO CHANGE — same rakha

    void switchTraffic(String service, String targetSlot, String environment) {
        script.echo "=== SWITCHING TRAFFIC → ${targetSlot} ==="

        script.sh """
            kubectl patch service ${service} \
                -n ${environment} \
                -p '{"spec":{"selector":{"slot":"${targetSlot}"}}}'
        """

        // verify switch actually hua
        String activeSlot = getActiveSlot(service, environment)
        if (activeSlot != targetSlot) {
            script.error("Switch FAILED — active: '${activeSlot}', expected: '${targetSlot}'")
        }

        script.echo "Traffic switched → ${activeSlot}"
    }

    // ─── ROLLBACK ──────────────────────────────────────────────
    // NO CHANGE — same rakha

    void rollback(String service, String environment) {
        script.echo "ROLLBACK: switching ${service} back to previous slot"

        String current  = getActiveSlot(service, environment)
        String previous = (current == 'blue') ? 'green' : 'blue'

        script.sh """
            kubectl patch service ${service} \
                -n ${environment} \
                -p '{"spec":{"selector":{"slot":"${previous}"}}}'
        """

        // verify rollback hua
        String nowActive = getActiveSlot(service, environment)
        if (nowActive != previous) {
            script.error("Rollback FAILED — still on '${nowActive}'")
        }

        script.echo "Rollback done. ${previous} is LIVE again."
    }

    // ─── FINAL STATUS ──────────────────────────────────────────
    // NO CHANGE — same rakha

    void finalStatus(String service, String environment) {
        script.echo "=== FINAL STATUS ==="

        script.sh "kubectl get all -n ${environment}"

        String liveSlot   = getActiveSlot(service, environment)
        String blueImage  = getImage(service, 'blue',  environment)
        String greenImage = getImage(service, 'green', environment)

        script.echo """
=== LIVE SLOT  === ${liveSlot}
=== BLUE  IMG  === ${blueImage}
=== GREEN IMG  === ${greenImage}
"""
    }

    // ─── PRIVATE HELPERS ───────────────────────────────────────
    // NO CHANGE — same rakha

    private String getActiveSlot(String service, String environment) {
        return script.sh(
            script: "kubectl get svc ${service} -n ${environment} -o jsonpath='{.spec.selector.slot}'",
            returnStdout: true
        ).trim() ?: 'blue'
    }

    private String getImage(String service, String slot, String environment) {
        return script.sh(
            script: "kubectl get deployment ${service}-${slot} -n ${environment} -o jsonpath='{.spec.template.spec.containers[0].image}'",
            returnStdout: true
        ).trim()
    }
}