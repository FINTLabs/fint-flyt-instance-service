package no.fintlabs.slack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class SlackAlertService {

    @Value("${fint.org-id}")
    private String orgId;

    @Value("${fint.application-id}")
    private String applicationId;

    @Value("${slack.webhook.url}")
    private String slackWebhookUrl;

    private final RestTemplate restTemplate;

    public SlackAlertService(
            RestTemplate restTemplate
    ) {
        this.restTemplate = restTemplate;
    }

    public void sendMessage(String message) {
        Map<String, String> payload = new HashMap<>();
        String formattedMessage = formatMessageWithPrefix(message);
        payload.put("text", formattedMessage);

        restTemplate.postForObject(slackWebhookUrl, payload, String.class);
    }

    private String formatMessageWithPrefix(String message) {
        return orgId + "-" + applicationId + "-" + message;
    }
}
