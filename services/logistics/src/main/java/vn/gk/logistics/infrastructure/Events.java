package vn.gk.logistics.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Phát sự kiện lên Kafka và ghi trạng thái realtime vào Redis. Lỗi ở đây không làm hỏng luồng nghiệp vụ. */
@Component
public class Events {
    private static final Logger log = LoggerFactory.getLogger(Events.class);
    private final KafkaTemplate<String, String> kafka;
    private final StringRedisTemplate redis;

    public Events(KafkaTemplate<String, String> kafka, StringRedisTemplate redis) {
        this.kafka = kafka;
        this.redis = redis;
    }

    public void publish(String topic, String key, String json) {
        try {
            kafka.send(topic, key, json);
        } catch (Exception e) {
            log.warn("Kafka không khả dụng ({}): {}", topic, e.getMessage());
        }
    }

    public void lastPosition(String vehicleCode, String json) {
        try {
            redis.opsForHash().put("vehicles:last", vehicleCode, json);
        } catch (Exception e) {
            log.warn("Redis không khả dụng: {}", e.getMessage());
        }
    }
}
