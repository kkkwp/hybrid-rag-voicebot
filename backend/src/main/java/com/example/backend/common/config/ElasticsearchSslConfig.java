package com.example.backend.common.config;

import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.elasticsearch.autoconfigure.Rest5ClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

@Configuration
public class ElasticsearchSslConfig {

    @Bean
    Rest5ClientBuilderCustomizer elasticsearchTlsCustomizer(
            @Value("${app.elasticsearch.ca-cert:../logstash/ca.crt}") String caCertPath
    ) {
        return new Rest5ClientBuilderCustomizer() {
            @Override
            public void customize(co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder builder) {
                // Keep the default endpoint/authentication customization from Spring Boot.
            }

            @Override
            public void customize(PoolingAsyncClientConnectionManagerBuilder builder) {
                builder.setTlsStrategy(ClientTlsStrategyBuilder.create()
                        .setSslContext(sslContext(caCertPath))
                        .buildAsync());
            }
        };
    }

    private SSLContext sslContext(String caCertPath) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate certificate;
            try (InputStream inputStream = Files.newInputStream(Path.of(caCertPath))) {
                certificate = certificateFactory.generateCertificate(inputStream);
            }

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("elasticsearch-ca", certificate);

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            return sslContext;
        } catch (Exception error) {
            throw new IllegalStateException("Failed to load Elasticsearch CA certificate: " + caCertPath, error);
        }
    }
}
