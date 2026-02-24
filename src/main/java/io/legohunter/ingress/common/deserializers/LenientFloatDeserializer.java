package io.legohunter.ingress.common.deserializers;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

public class LenientFloatDeserializer extends JsonDeserializer<Float> {
    private final JsonDeserializer<Float> floatDeserializer = new NumberDeserializers.FloatDeserializer(Float.class, null);

    @Override
    public Float deserialize(JsonParser parser, DeserializationContext context) throws IOException, JacksonException {
        String value = parser.getValueAsString();

        if (StringUtils.isEmpty(value)) {
            return null;
        }

        try {
            // Delegate to Jackson's real implementation
            return floatDeserializer.deserialize(parser, context);
        } catch (Exception e) {
            return null;
        }
    }
}
