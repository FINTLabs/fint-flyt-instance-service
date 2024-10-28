package no.fintlabs.slack;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static no.fintlabs.resourceserver.UrlPaths.INTERNAL_API;

@RestController
@RequestMapping(INTERNAL_API)
public class NotificationController {

    private final SlackAlertService slackAlertService;

    public NotificationController(SlackAlertService slackAlertService) {
        this.slackAlertService = slackAlertService;
    }

    @GetMapping("notify")
    public ResponseEntity<String> notifySlack(@RequestParam String message) {
        slackAlertService.sendMessage(message);
        return ResponseEntity.ok("Notification sent to Slack");
    }
}
