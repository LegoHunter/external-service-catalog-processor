package io.legohunter.ingress.common.deserializers;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

public class LenientIntegerDeserializer extends JsonDeserializer<Integer> {
    private final JsonDeserializer<Integer> deserializer = new NumberDeserializers.IntegerDeserializer(Integer.class, null);

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException, JacksonException {
        String value = parser.getValueAsString();

        if (StringUtils.isEmpty(value)) {
            return null;
        }

        try {
            // Delegate to Jackson's real implementation
            return deserializer.deserialize(parser, context);
        } catch (Exception e) {
            return null;
        }
    }
}
