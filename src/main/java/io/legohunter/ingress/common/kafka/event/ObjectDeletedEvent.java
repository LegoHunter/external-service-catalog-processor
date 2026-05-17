package io.legohunter.ingress.common.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Setter
@Getter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectDeletedEvent {
    private String bucket;
    private String key;
    private String eventName;
    private Long currentTimeStamp;
}
