package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.webhook.DeliveryStatus;
import io.runcycles.admin.model.webhook.WebhookDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Spy private ObjectMapper objectMapper = createObjectMapper();
    @InjectMocks private WebhookDeliveryRepository repository;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
    }

    // ---- save() ----

    @Test
    void save_setsDeliveryIdAndTimestamp() {
        WebhookDelivery delivery = WebhookDelivery.builder()
                .subscriptionId("whsub_1")
                .eventId("evt_1")
                .eventType(EventType.TENANT_CREATED)
                .status(DeliveryStatus.PENDING)
                .build();

        repository.save(delivery);

        assertThat(delivery.getDeliveryId()).startsWith("del_");
        assertThat(delivery.getAttemptedAt()).isNotNull();
        verify(jedis).eval(anyString(), anyList(), anyList());
    }

    @Test
    void save_callsLuaWithCorrectKeysAndArgs() throws Exception {
        WebhookDelivery delivery = WebhookDelivery.builder()
                .subscriptionId("whsub_1")
                .eventId("evt_1")
                .eventType(EventType.BUDGET_CREATED)
                .status(DeliveryStatus.PENDING)
                .build();

        repository.save(delivery);

        verify(jedis).eval(anyString(), argThat((List<String> keys) -> {
            return keys.size() == 2
                && keys.get(0).startsWith("delivery:del_")
                && keys.get(1).equals("deliveries:whsub_1");
        }), argThat((List<String> args) -> {
            try {
                WebhookDelivery stored = objectMapper.readValue(args.get(0), WebhookDelivery.class);
                return "whsub_1".equals(stored.getSubscriptionId())
                    && "evt_1".equals(stored.getEventId());
            } catch (Exception e) {
                return false;
            }
        }));
    }

    // ---- findById() ----

    @Test
    void findById_success_returnsDelivery() throws Exception {
        WebhookDelivery delivery = WebhookDelivery.builder()
                .deliveryId("del_abc123")
                .subscriptionId("whsub_1")
                .eventId("evt_1")
                .status(DeliveryStatus.SUCCESS)
                .attemptedAt(Instant.now())
                .build();
        String json = objectMapper.writeValueAsString(delivery);
        when(jedis.get("delivery:del_abc123")).thenReturn(json);

        WebhookDelivery result = repository.findById("del_abc123");

        assertThat(result.getDeliveryId()).isEqualTo("del_abc123");
        assertThat(result.getSubscriptionId()).isEqualTo("whsub_1");
        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
    }

    @Test
    void findById_notFound_throwsGovernanceException() {
        when(jedis.get("delivery:del_missing")).thenReturn(null);

        assertThatThrownBy(() -> repository.findById("del_missing"))
                .isInstanceOf(GovernanceException.class)
                .satisfies(ex -> {
                    GovernanceException ge = (GovernanceException) ex;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                    assertThat(ge.getMessage()).contains("del_missing");
                });
    }

    // ---- listBySubscription() ----

    @Test
    void listBySubscription_returnsDeliveries() throws Exception {
        List<String> ids = List.of("del_1", "del_2");
        when(jedis.zrevrangeByScore(eq("deliveries:whsub_1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        WebhookDelivery d1 = WebhookDelivery.builder().deliveryId("del_1").subscriptionId("whsub_1").eventId("evt_1").status(DeliveryStatus.SUCCESS).attemptedAt(Instant.now()).build();
        WebhookDelivery d2 = WebhookDelivery.builder().deliveryId("del_2").subscriptionId("whsub_1").eventId("evt_2").status(DeliveryStatus.FAILED).attemptedAt(Instant.now()).build();
        String d1Json = objectMapper.writeValueAsString(d1);
        String d2Json = objectMapper.writeValueAsString(d2);
        when(jedis.get("delivery:del_1")).thenReturn(d1Json);
        when(jedis.get("delivery:del_2")).thenReturn(d2Json);

        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", null, null, null, null, 50);

        assertThat(result).hasSize(2);
    }

    @Test
    void listBySubscription_filtersByStatus() throws Exception {
        List<String> ids = List.of("del_1", "del_2");
        when(jedis.zrevrangeByScore(eq("deliveries:whsub_1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        WebhookDelivery d1 = WebhookDelivery.builder().deliveryId("del_1").subscriptionId("whsub_1").eventId("evt_1").status(DeliveryStatus.SUCCESS).attemptedAt(Instant.now()).build();
        WebhookDelivery d2 = WebhookDelivery.builder().deliveryId("del_2").subscriptionId("whsub_1").eventId("evt_2").status(DeliveryStatus.FAILED).attemptedAt(Instant.now()).build();
        String d1Json = objectMapper.writeValueAsString(d1);
        String d2Json = objectMapper.writeValueAsString(d2);
        when(jedis.get("delivery:del_1")).thenReturn(d1Json);
        when(jedis.get("delivery:del_2")).thenReturn(d2Json);

        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", "FAILED", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void listBySubscription_withTimeRange() throws Exception {
        Instant from = Instant.ofEpochMilli(1000);
        Instant to = Instant.ofEpochMilli(2000);

        List<String> ids = List.of("del_1");
        when(jedis.zrevrangeByScore(eq("deliveries:whsub_1"), eq(2000.0), eq(1000.0), eq(0), anyInt())).thenReturn(ids);

        WebhookDelivery d1 = WebhookDelivery.builder().deliveryId("del_1").subscriptionId("whsub_1").eventId("evt_1").status(DeliveryStatus.SUCCESS).attemptedAt(Instant.now()).build();
        String d1Json = objectMapper.writeValueAsString(d1);
        when(jedis.get("delivery:del_1")).thenReturn(d1Json);

        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", null, from, to, null, 50);

        assertThat(result).hasSize(1);
        verify(jedis).zrevrangeByScore(eq("deliveries:whsub_1"), eq(2000.0), eq(1000.0), eq(0), anyInt());
    }

    @Test
    void listBySubscription_withCursor() throws Exception {
        when(jedis.zscore("deliveries:whsub_1", "del_cursor")).thenReturn(5000.0);

        List<String> ids = List.of("del_2");
        when(jedis.zrevrangeByScore(eq("deliveries:whsub_1"), eq(4999.0), eq(Double.NEGATIVE_INFINITY), eq(0), anyInt())).thenReturn(ids);

        WebhookDelivery d2 = WebhookDelivery.builder().deliveryId("del_2").subscriptionId("whsub_1").eventId("evt_2").status(DeliveryStatus.SUCCESS).attemptedAt(Instant.now()).build();
        String d2Json = objectMapper.writeValueAsString(d2);
        when(jedis.get("delivery:del_2")).thenReturn(d2Json);

        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", null, null, null, "del_cursor", 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDeliveryId()).isEqualTo("del_2");
    }

    @Test
    void listBySubscription_zeroLimit_returnsEmpty() {
        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", null, null, null, null, 0);
        assertThat(result).isEmpty();
    }

    // ---- update() ----

    @Test
    void update_overwritesExistingDelivery() throws Exception {
        WebhookDelivery delivery = WebhookDelivery.builder()
                .deliveryId("del_abc123")
                .subscriptionId("whsub_1")
                .eventId("evt_1")
                .status(DeliveryStatus.SUCCESS)
                .responseStatus(200)
                .attemptedAt(Instant.now())
                .build();
        when(jedis.get("delivery:del_abc123")).thenReturn("{\"delivery_id\":\"del_abc123\"}");

        repository.update(delivery);

        verify(jedis).set(eq("delivery:del_abc123"), argThat(json -> json.contains("\"status\":\"SUCCESS\"")));
    }

    @Test
    void save_preservesExistingTimestamp() {
        Instant fixedTime = Instant.parse("2025-06-15T10:00:00Z");
        WebhookDelivery delivery = WebhookDelivery.builder()
                .subscriptionId("whsub_1")
                .eventId("evt_1")
                .eventType(EventType.TENANT_CREATED)
                .status(DeliveryStatus.PENDING)
                .attemptedAt(fixedTime)
                .build();

        repository.save(delivery);

        assertThat(delivery.getAttemptedAt()).isEqualTo(fixedTime);
    }

    @Test
    void save_redisException_throwsRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        WebhookDelivery delivery = WebhookDelivery.builder()
                .subscriptionId("whsub_1")
                .eventId("evt_1")
                .status(DeliveryStatus.PENDING)
                .build();

        assertThatThrownBy(() -> repository.save(delivery))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to save webhook delivery");
    }

    @Test
    void findById_redisException_throwsRuntimeException() {
        when(jedis.get("delivery:del_err")).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> repository.findById("del_err"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read webhook delivery");
    }

    @Test
    void listBySubscription_missingDeliveryData_skipsGracefully() throws Exception {
        List<String> ids = List.of("del_gone", "del_ok");
        when(jedis.zrevrangeByScore(eq("deliveries:whsub_1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        WebhookDelivery d = WebhookDelivery.builder().deliveryId("del_ok").subscriptionId("whsub_1").eventId("evt_1").status(DeliveryStatus.SUCCESS).attemptedAt(Instant.now()).build();
        String dJson = objectMapper.writeValueAsString(d);
        when(jedis.get("delivery:del_gone")).thenReturn(null);
        when(jedis.get("delivery:del_ok")).thenReturn(dJson);

        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDeliveryId()).isEqualTo("del_ok");
    }

    @Test
    void listBySubscription_parseFailure_skipsGracefully() throws Exception {
        List<String> ids = List.of("del_bad", "del_ok");
        when(jedis.zrevrangeByScore(eq("deliveries:whsub_1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        WebhookDelivery d = WebhookDelivery.builder().deliveryId("del_ok").subscriptionId("whsub_1").eventId("evt_1").status(DeliveryStatus.SUCCESS).attemptedAt(Instant.now()).build();
        String dJson = objectMapper.writeValueAsString(d);
        when(jedis.get("delivery:del_bad")).thenReturn("{invalid json}");
        when(jedis.get("delivery:del_ok")).thenReturn(dJson);

        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", null, null, null, null, 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void listBySubscription_cursorNotInIndex_ignoresCursor() throws Exception {
        when(jedis.zscore("deliveries:whsub_1", "del_unknown")).thenReturn(null);

        List<String> ids = List.of("del_1");
        when(jedis.zrevrangeByScore(eq("deliveries:whsub_1"), eq(Double.POSITIVE_INFINITY), eq(Double.NEGATIVE_INFINITY), eq(0), anyInt())).thenReturn(ids);

        WebhookDelivery d = WebhookDelivery.builder().deliveryId("del_1").subscriptionId("whsub_1").eventId("evt_1").status(DeliveryStatus.SUCCESS).attemptedAt(Instant.now()).build();
        String dJson = objectMapper.writeValueAsString(d);
        when(jedis.get("delivery:del_1")).thenReturn(dJson);

        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", null, null, null, "del_unknown", 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void listBySubscription_emptyResults_returnsEmptyList() {
        when(jedis.zrevrangeByScore(eq("deliveries:whsub_1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(List.of());

        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", null, null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void listBySubscription_respectsLimit() throws Exception {
        List<String> ids = List.of("del_1", "del_2", "del_3");
        when(jedis.zrevrangeByScore(eq("deliveries:whsub_1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        WebhookDelivery d1 = WebhookDelivery.builder().deliveryId("del_1").subscriptionId("whsub_1").eventId("evt_1").status(DeliveryStatus.SUCCESS).attemptedAt(Instant.now()).build();
        WebhookDelivery d2 = WebhookDelivery.builder().deliveryId("del_2").subscriptionId("whsub_1").eventId("evt_2").status(DeliveryStatus.SUCCESS).attemptedAt(Instant.now()).build();
        String d1Json = objectMapper.writeValueAsString(d1);
        String d2Json = objectMapper.writeValueAsString(d2);
        when(jedis.get("delivery:del_1")).thenReturn(d1Json);
        when(jedis.get("delivery:del_2")).thenReturn(d2Json);

        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", null, null, null, null, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void listBySubscription_statusFilterWithNullDeliveryStatus() throws Exception {
        List<String> ids = List.of("del_1");
        when(jedis.zrevrangeByScore(eq("deliveries:whsub_1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        WebhookDelivery d1 = WebhookDelivery.builder().deliveryId("del_1").subscriptionId("whsub_1").eventId("evt_1").attemptedAt(Instant.now()).build();
        // status is null
        String d1Json = objectMapper.writeValueAsString(d1);
        when(jedis.get("delivery:del_1")).thenReturn(d1Json);

        List<WebhookDelivery> result = repository.listBySubscription("whsub_1", "SUCCESS", null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void update_redisException_throwsRuntimeException() {
        when(jedis.get("delivery:del_1")).thenReturn("{\"delivery_id\":\"del_1\"}");
        when(jedis.set(anyString(), anyString())).thenThrow(new RuntimeException("Redis down"));

        WebhookDelivery delivery = WebhookDelivery.builder()
                .deliveryId("del_1").subscriptionId("whsub_1").eventId("evt_1")
                .status(DeliveryStatus.SUCCESS).attemptedAt(Instant.now()).build();

        assertThatThrownBy(() -> repository.update(delivery))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to update webhook delivery");
    }

    @Test
    void update_notFound_throwsGovernanceException() {
        WebhookDelivery delivery = WebhookDelivery.builder()
                .deliveryId("del_missing")
                .subscriptionId("whsub_1")
                .eventId("evt_1")
                .status(DeliveryStatus.FAILED)
                .attemptedAt(Instant.now())
                .build();
        when(jedis.get("delivery:del_missing")).thenReturn(null);

        assertThatThrownBy(() -> repository.update(delivery))
                .isInstanceOf(GovernanceException.class)
                .satisfies(ex -> {
                    GovernanceException ge = (GovernanceException) ex;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                });
    }
}
