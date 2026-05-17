package io.legohunter.ingress.common.kafka.event;

import lombok.*;

@Setter
@Getter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectUploadedEvent {
    private String bucket;
    private String key;
    private String eventName;
    private Long currentTimeStamp;
}