package test.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class CuttingOptimizationApplication implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CuttingOptimizationApplication.class);

    static {
        try {
            com.google.ortools.Loader.loadNativeLibraries();
        } catch (Exception e) {
            log.error("Failed to load OR-Tools native libraries", e);
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(CuttingOptimizationApplication.class, args);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(0);
    }
}
