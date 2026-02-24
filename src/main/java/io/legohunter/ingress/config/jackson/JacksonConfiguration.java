package io.legohunter.ingress.config.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.legohunter.ingress.common.jackson.module.XmlModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.xml.stream.XMLInputFactory;

@Configuration
public class JacksonConfiguration {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public XmlMapper xmlMapper() {
        XMLInputFactory inputFactory = XMLInputFactory.newFactory();
        inputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);

        XmlFactory factory = XmlFactory.builder()
                .xmlInputFactory(inputFactory)
                .build();

        XmlMapper mapper = new XmlMapper(factory);
        mapper.registerModule(new XmlModule());
        return mapper;
    }
}
