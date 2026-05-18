package io.legohunter.ingress.common.jackson.module;

import com.fasterxml.jackson.databind.module.SimpleModule;
import io.legohunter.ingress.common.deserializers.HtmlUnescapingStringDeserializer;
import io.legohunter.ingress.common.deserializers.LenientFloatDeserializer;
import io.legohunter.ingress.common.deserializers.LenientIntegerDeserializer;
import org.springframework.stereotype.Component;

@Component
public class XmlModule extends SimpleModule {

    public XmlModule() {
        addDeserializer(Integer.class, new LenientIntegerDeserializer());
        addDeserializer(Float.class, new LenientFloatDeserializer());
        addDeserializer(String.class, new HtmlUnescapingStringDeserializer());
    }
}
