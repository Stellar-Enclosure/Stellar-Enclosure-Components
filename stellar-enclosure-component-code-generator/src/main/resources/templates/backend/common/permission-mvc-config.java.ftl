package [=permissionMvcPackage];

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.egon.cola.platform.tianquan.jianshen.starter.field.PermissionJsonMapperFactory;

/** Applies permission field rules to HTTP JSON without mutating the shared mapper. */
@Configuration(value = "cqersPermissionMvcConfiguration", proxyBeanMethods = false)
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CqersPermissionMvcConfiguration implements WebMvcConfigurer {

    @Qualifier("permissionJsonMapperFactory")
    private final PermissionJsonMapperFactory fields;

    @Qualifier("jacksonObjectMapper")
    private final ObjectMapper transportMapper;

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        var preferred = converters.stream().filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast).findFirst()
                .orElseGet(() -> new MappingJackson2HttpMessageConverter(transportMapper));
        int jsonSlot = java.util.stream.IntStream.range(0, converters.size())
                .filter(index -> converters.get(index).canRead(java.util.Map.class, MediaType.APPLICATION_JSON)
                        || converters.get(index).canWrite(java.util.Map.class, MediaType.APPLICATION_JSON))
                .findFirst().orElse(converters.size());
        int current = converters.indexOf(preferred);
        if (current < 0) converters.add(jsonSlot, preferred);
        else if (current > jsonSlot) { converters.remove(current); converters.add(jsonSlot, preferred); }
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter json) {
                json.setObjectMapper(fields.forTransport(json.getObjectMapper()));
            }
        }
    }
}
