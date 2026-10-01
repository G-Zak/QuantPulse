package com.quantpulse.alerts.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A notification waiting to be sent.
 *
 * Saved in the same transaction as the firing. We don't send inside the transaction
 * because a rollback can't take back an email.
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firing_id", nullable = false)
    private Long firingId;

    @Column(nullable = false, length = 32)
    private String channel = "LOG";

    @Column(nullable = false)
    private String recipient;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected NotificationOutbox() {
    }

    public NotificationOutbox(Long firingId, String channel, String recipient, String payload) {
        this.firingId = firingId;
        this.channel = channel;
        this.recipient = recipient;
        this.payload = payload;
    }

    public void markDelivered() {
        this.deliveredAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    public Long getId() { return id; }
    public Long getFiringId() { return firingId; }
    public String getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public int getAttempts() { return attempts; }
}
