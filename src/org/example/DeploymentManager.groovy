package org.example

class DeploymentManager implements Serializable {

    private def script

    static final List SERVICES = [
        'attendance', 'employee', 'salary',
        'frontend', 'notification', 'elasticsearch', 'mysql'
    ]

    static final Map ENV_CONFIG = [
        dev:     [requiresApproval: false, strategy: 'canary'],
        staging: [requiresApproval: false, strategy: 'bluegreen'],
        prod:    [requiresApproval: true,  strategy: 'bluegreen']
    ]

    DeploymentManager(def script) {
        this.script = script
    }

    boolean validateConfig(String service, String environment, String version) {
        script.echo "Validating: service=${service}, env=${environment}, version=${version}"

        if (!SERVICES.contains(service)) {
            script.error "Unknown service '${service}'. Valid: ${SERVICES}"
        }
        if (!ENV_CONFIG.containsKey(environment)) {
            script.error "Unknown environment '${environment}'"
        }
        if (!version?.matches(/^\d+\.\d+\.\d+(-[\w.]+)?$/)) {
            script.error "Invalid semver '${version}'. Expected format: 1.0.0"
        }

        script.echo "Validation passed"
        return true
    }

    void deploy(String service, String environment, String version) {
        String strategy = ENV_CONFIG[environment].strategy
        script.echo "Strategy for ${environment}: ${strategy.toUpperCase()}"

        if (strategy == 'bluegreen') {
            new BlueGreenDeployment(script).deploy(service, version, environment)
        } else if (strategy == 'canary') {
            new CanaryDeployment(script).deploy(service, version, environment)
        } else {
            script.error "Unknown strategy: ${strategy}"
        }
    }

    void rollback(String service, String environment) {
        String strategy = ENV_CONFIG[environment].strategy
        script.echo "Rolling back ${service} on ${environment} using ${strategy}"

        if (strategy == 'bluegreen') {
            new BlueGreenDeployment(script).rollback(service, environment)
        } else {
            new CanaryDeployment(script).rollback(service, environment)
        }
    }
}