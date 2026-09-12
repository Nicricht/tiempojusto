package cl.tiempojusto.app.config;

import cl.tiempojusto.app.payment.EphemeralMercadoPagoInstrumentResolver;
import cl.tiempojusto.app.payment.JdbcProviderPaymentStateRepository;
import cl.tiempojusto.app.payment.MercadoPagoHttpTransport;
import cl.tiempojusto.app.payment.MercadoPagoPaymentQueryClient;
import cl.tiempojusto.app.payment.MercadoPagoWebhookSignatureVerifier;
import cl.tiempojusto.finance.payment.PaymentPort;
import cl.tiempojusto.finance.payment.provider.MercadoPagoPaymentPort;
import cl.tiempojusto.finance.payment.provider.MercadoPagoTransport;
import cl.tiempojusto.finance.payment.provider.PaymentInstrumentResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "tiempojusto.payment.provider", havingValue = "MERCADO_PAGO")
public class MercadoPagoPaymentConfiguration {

    @Bean
    public EphemeralMercadoPagoInstrumentResolver mercadoPagoInstrumentResolver(
            @Value("${tiempojusto.payment.mercado-pago.instrument-ttl-seconds:600}") long ttlSeconds) {
        return new EphemeralMercadoPagoInstrumentResolver(Duration.ofSeconds(ttlSeconds));
    }

    @Bean
    public MercadoPagoTransport mercadoPagoTransport(
            RestClient.Builder builder,
            @Value("${tiempojusto.payment.mercado-pago.base-url}") String baseUrl,
            @Value("${tiempojusto.payment.mercado-pago.access-token}") String accessToken) {
        return new MercadoPagoHttpTransport(builder, baseUrl, accessToken);
    }

    @Bean
    public MercadoPagoPaymentQueryClient mercadoPagoPaymentQueryClient(
            RestClient.Builder builder,
            @Value("${tiempojusto.payment.mercado-pago.base-url}") String baseUrl,
            @Value("${tiempojusto.payment.mercado-pago.access-token}") String accessToken) {
        return new MercadoPagoPaymentQueryClient(builder, baseUrl, accessToken);
    }

    @Bean
    public MercadoPagoWebhookSignatureVerifier mercadoPagoWebhookSignatureVerifier(
            @Value("${tiempojusto.payment.mercado-pago.webhook-secret}") String webhookSecret) {
        return new MercadoPagoWebhookSignatureVerifier(webhookSecret);
    }

    @Bean
    public PaymentPort mercadoPagoPaymentPort(
            MercadoPagoTransport transport,
            PaymentInstrumentResolver instrumentResolver,
            JdbcProviderPaymentStateRepository repository) {
        return new MercadoPagoPaymentPort(transport, instrumentResolver, repository);
    }
}
