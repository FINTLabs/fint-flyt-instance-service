package no.fintlabs.slack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class SlackAlertService {

    private final RestTemplate restTemplate;

    @Value("${slack.webhook.url}")
    private String slackWebhookUrl;

    public SlackAlertService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void sendMessage(String message) {
        Map<String, String> payload = new HashMap<>();
        payload.put("text", message);

        restTemplate.postForObject(slackWebhookUrl, payload, String.class);
    }
}
