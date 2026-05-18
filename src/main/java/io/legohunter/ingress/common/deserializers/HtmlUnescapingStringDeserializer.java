package io.legohunter.ingress.common.deserializers;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import org.apache.commons.lang3.StringEscapeUtils;

import java.io.IOException;

public class HtmlUnescapingStringDeserializer extends JsonDeserializer<String> {
    private final JsonDeserializer<String> deserializer = new StringDeserializer();

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException, JacksonException {
        return StringEscapeUtils.unescapeHtml4(deserializer.deserialize(parser, context));
    }
}
