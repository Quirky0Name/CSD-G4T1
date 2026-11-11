package com.g4t1.users.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Telegram's sendMessage, as plain text. The bot token is part of the URL, so neither the URL nor the
 * text is ever logged, and a failure logs the status code or the exception's class only.
 */
@Component
public class TelegramChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    // Telegram refuses longer messages; it counts UTF-16 code units, which is what a Java String's length is
    private static final int MAX_LENGTH = 4096;
    private static final String ELLIPSIS = "…";

    private final RestClient rest;
    private final String botToken;

    public TelegramChannel(RestClient.Builder builder, @Value("${users.telegram-api-url}") String apiUrl,
                           @Value("${users.telegram-bot-token:}") String botToken) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(TIMEOUT);
        this.rest = builder.requestFactory(requests).baseUrl(apiUrl).build();
        this.botToken = botToken;
    }

    public Outcome send(UUID userId, Long chatId, String text) {
        if (botToken.isBlank()) {
            return Outcome.NOT_CONFIGURED;
        }
        // the PUT refuses an enabled channel with no chat id, so this is only an older or hand edited row
        if (chatId == null) {
            log.warn("telegram message to user {} failed: no chat id", userId);
            return Outcome.FAILED;
        }
        try {
            rest.post()
                    .uri("/bot{token}/sendMessage", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", cut(text), "disable_web_page_preview", true))
                    .retrieve()
                    .toBodilessEntity();
            return Outcome.SENT;
        } catch (RestClientResponseException e) {
            log.warn("telegram message to user {} failed: status {}", userId, e.getStatusCode().value());
        } catch (RestClientException e) {
            // an interrupt is the sender's deadline passing, and the sender logs that
            if (!Thread.currentThread().isInterrupted()) {
                log.warn("telegram message to user {} failed: {}", userId, e.getClass().getSimpleName());
            }
        }
        return Outcome.FAILED;
    }

    // ends with an ellipsis when cut, and never leaves half of an emoji (two units) before it
    private static String cut(String text) {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        int end = MAX_LENGTH - ELLIPSIS.length();
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + ELLIPSIS;
    }
}
