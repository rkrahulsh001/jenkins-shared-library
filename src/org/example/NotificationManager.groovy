package org.example

class NotificationManager implements Serializable {

    private def script

    NotificationManager(def script) {
        this.script = script
    }

    // ── SLACK NOTIFICATION ────────────────────────────────────

    void sendSlack(String status, Map details) {
        try {
            String color   = getColor(status)
            String emoji   = getEmoji(status)
            String message = buildSlackMessage(status, emoji, details)

            script.slackSend(
                channel    : '#jenkins-notifications',
                color      : color,
                message    : message,
                tokenCredentialId: 'slack-token'
            )

            script.echo "Slack notification sent: ${status}"

        } catch (Exception e) {
            // graceful failure — notification fail hone se pipeline fail nahi hogi
            script.echo "WARNING: Slack notification failed: ${e.message}"
        }
    }

    // ── EMAIL NOTIFICATION ────────────────────────────────────

    void sendEmail(String status, Map details) {
        try {
            String subject = buildEmailSubject(status, details)
            String body    = buildEmailBody(status, details)

            script.emailext(
                subject     : subject,
                body        : body,
                mimeType    : 'text/html',
                to          : details.recipients ?: 'YOUR_EMAIL@gmail.com',
                attachLog   : status == 'FAILURE'   // failure pe log attach karo
            )

            script.echo "Email notification sent: ${status}"

        } catch (Exception e) {
            script.echo "WARNING: Email notification failed: ${e.message}"
        }
    }

    // ── MS TEAMS NOTIFICATION ─────────────────────────────────

    void sendTeams(String status, Map details) {
        try {
            String color   = getTeamsColor(status)
            String emoji   = getEmoji(status)
            String message = buildTeamsMessage(status, emoji, details)

            // Teams webhook call
            script.withCredentials([
                script.string(
                    credentialsId: 'teams-webhook',
                    variable: 'TEAMS_URL'
                )
            ]) {
                script.sh("""
                    curl -s -X POST "\$TEAMS_URL" \
                        -H "Content-Type: application/json" \
                        -d '${message}' || echo "Teams notification failed"
                """)
            }

            script.echo "Teams notification sent: ${status}"

        } catch (Exception e) {
            script.echo "WARNING: Teams notification failed: ${e.message}"
        }
    }

    // ── SEND ALL NOTIFICATIONS ────────────────────────────────

    void notifyAll(String status, Map details) {
        sendSlack(status, details)
        sendEmail(status, details)
        sendTeams(status, details)
    }

    // ── PRIVATE — MESSAGE BUILDERS ────────────────────────────

    private String buildSlackMessage(String status, String emoji, Map d) {
        return """
${emoji} *${status}: ${d.jobName} #${d.buildNumber}*
> *Branch*    : ${d.branch}
> *Service*   : ${d.service}
> *Version*   : ${d.version}
> *Env*       : ${d.environment}
> *Duration*  : ${d.duration}
> *Changes*   : ${d.changes}
> *Build URL* : ${d.buildUrl}
        """.trim()
    }

    private String buildEmailSubject(String status, Map d) {
        String emoji = getEmoji(status)
        return "${emoji} ${status}: ${d.jobName} #${d.buildNumber} [${d.branch}]"
    }

    private String buildEmailBody(String status, Map d) {
        String color = getHtmlColor(status)
        return """
<!DOCTYPE html>
<html>
<body style="font-family: Arial, sans-serif; margin: 0; padding: 20px;">

  <div style="background-color: ${color};
              padding: 15px;
              border-radius: 8px;
              margin-bottom: 20px;">
    <h2 style="color: white; margin: 0;">
      ${getEmoji(status)} Build ${status}
    </h2>
  </div>

  <table style="width: 100%;
                border-collapse: collapse;
                margin-bottom: 20px;">
    <tr style="background-color: #f2f2f2;">
      <td style="padding: 10px; border: 1px solid #ddd; font-weight: bold;">Job Name</td>
      <td style="padding: 10px; border: 1px solid #ddd;">${d.jobName}</td>
    </tr>
    <tr>
      <td style="padding: 10px; border: 1px solid #ddd; font-weight: bold;">Build Number</td>
      <td style="padding: 10px; border: 1px solid #ddd;">#${d.buildNumber}</td>
    </tr>
    <tr style="background-color: #f2f2f2;">
      <td style="padding: 10px; border: 1px solid #ddd; font-weight: bold;">Branch</td>
      <td style="padding: 10px; border: 1px solid #ddd;">${d.branch}</td>
    </tr>
    <tr>
      <td style="padding: 10px; border: 1px solid #ddd; font-weight: bold;">Service</td>
      <td style="padding: 10px; border: 1px solid #ddd;">${d.service}</td>
    </tr>
    <tr style="background-color: #f2f2f2;">
      <td style="padding: 10px; border: 1px solid #ddd; font-weight: bold;">Version</td>
      <td style="padding: 10px; border: 1px solid #ddd;">${d.version}</td>
    </tr>
    <tr>
      <td style="padding: 10px; border: 1px solid #ddd; font-weight: bold;">Environment</td>
      <td style="padding: 10px; border: 1px solid #ddd;">${d.environment}</td>
    </tr>
    <tr style="background-color: #f2f2f2;">
      <td style="padding: 10px; border: 1px solid #ddd; font-weight: bold;">Duration</td>
      <td style="padding: 10px; border: 1px solid #ddd;">${d.duration}</td>
    </tr>
    <tr>
      <td style="padding: 10px; border: 1px solid #ddd; font-weight: bold;">Changes</td>
      <td style="padding: 10px; border: 1px solid #ddd;">${d.changes}</td>
    </tr>
  </table>

  <div style="margin-bottom: 20px;">
    <a href="${d.buildUrl}"
       style="background-color: #0066cc;
              color: white;
              padding: 10px 20px;
              text-decoration: none;
              border-radius: 5px;">
      View Build Logs
    </a>
  </div>

  <p style="color: #666; font-size: 12px;">
    This is an automated notification from Jenkins CI/CD Pipeline.
  </p>

</body>
</html>
        """.trim()
    }

    private String buildTeamsMessage(String status, String emoji, Map d) {
        String color = getTeamsColor(status)
        return """
{
    "@type": "MessageCard",
    "@context": "http://schema.org/extensions",
    "themeColor": "${color}",
    "summary": "${status}: ${d.jobName} #${d.buildNumber}",
    "sections": [{
        "activityTitle": "${emoji} **${status}: ${d.jobName}**",
        "activitySubtitle": "Build #${d.buildNumber} | Branch: ${d.branch}",
        "facts": [
            {"name": "Service",     "value": "${d.service}"},
            {"name": "Version",     "value": "${d.version}"},
            {"name": "Environment", "value": "${d.environment}"},
            {"name": "Duration",    "value": "${d.duration}"},
            {"name": "Changes",     "value": "${d.changes}"}
        ],
        "markdown": true
    }],
    "potentialAction": [{
        "@type": "OpenUri",
        "name": "View Build",
        "targets": [{"os": "default", "uri": "${d.buildUrl}"}]
    }]
}
        """.trim()
    }

    // ── PRIVATE — HELPERS ─────────────────────────────────────

    private String getColor(String status) {
        switch(status) {
            case 'SUCCESS'  : return 'good'
            case 'FAILURE'  : return 'danger'
            case 'UNSTABLE' : return 'warning'
            case 'STARTED'  : return '#439FE0'
            default         : return '#808080'
        }
    }

    private String getHtmlColor(String status) {
        switch(status) {
            case 'SUCCESS'  : return '#28a745'
            case 'FAILURE'  : return '#dc3545'
            case 'UNSTABLE' : return '#ffc107'
            case 'STARTED'  : return '#17a2b8'
            default         : return '#6c757d'
        }
    }

    private String getTeamsColor(String status) {
        switch(status) {
            case 'SUCCESS'  : return '28a745'
            case 'FAILURE'  : return 'dc3545'
            case 'UNSTABLE' : return 'ffc107'
            case 'STARTED'  : return '17a2b8'
            default         : return '6c757d'
        }
    }

    private String getEmoji(String status) {
        switch(status) {
            case 'SUCCESS'  : return 'SUCCESS'
            case 'FAILURE'  : return 'FAILED'
            case 'UNSTABLE' : return 'UNSTABLE'
            case 'STARTED'  : return 'STARTED'
            default         : return 'INFO'
        }
    }
}