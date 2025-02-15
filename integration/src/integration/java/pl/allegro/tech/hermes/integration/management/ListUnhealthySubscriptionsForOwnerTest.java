package pl.allegro.tech.hermes.integration.management;

import com.google.common.collect.ImmutableSet;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.GenericType;
import org.javers.common.collections.Lists;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import pl.allegro.tech.hermes.api.MonitoringDetails;
import pl.allegro.tech.hermes.api.OwnerId;
import pl.allegro.tech.hermes.api.Subscription;
import pl.allegro.tech.hermes.api.Topic;
import pl.allegro.tech.hermes.api.UnhealthySubscription;
import pl.allegro.tech.hermes.integration.IntegrationTest;
import pl.allegro.tech.hermes.integration.env.SharedServices;
import pl.allegro.tech.hermes.integration.helper.PrometheusEndpoint;
import pl.allegro.tech.hermes.integration.helper.PrometheusEndpoint.PrometheusTopicResponse;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;
import static org.assertj.core.api.Assertions.catchThrowable;
import static pl.allegro.tech.hermes.api.MonitoringDetails.Severity;
import static pl.allegro.tech.hermes.api.SubscriptionHealthProblem.malfunctioning;
import static pl.allegro.tech.hermes.integration.helper.PrometheusEndpoint.PrometheusSubscriptionResponseBuilder.builder;
import static pl.allegro.tech.hermes.integration.test.HermesAssertions.assertThat;
import static pl.allegro.tech.hermes.test.helper.builder.SubscriptionBuilder.subscription;
import static pl.allegro.tech.hermes.test.helper.builder.TopicBuilder.randomTopic;
import static pl.allegro.tech.hermes.test.helper.builder.TopicBuilder.topic;

public class ListUnhealthySubscriptionsForOwnerTest extends IntegrationTest {

    private PrometheusEndpoint prometheusEndpoint;
    private final Client httpClient = ClientBuilder.newClient();

    @BeforeMethod
    public void initializeAlways() {
        prometheusEndpoint = new PrometheusEndpoint(SharedServices.services().prometheusHttpMock());
    }

    @Test
    public void shouldNotListHealthySubscriptions() {
        // given
        Topic topic = operations.buildTopic(randomTopic("group", "topic").build());
        createSubscriptionForOwner(topic, "s1", "Team A");
        createSubscriptionForOwner(topic, "s2", "Team B");

        // then
        assertThat(listUnhealthySubscriptionsForOwner("Team A")).isEmpty();
        assertThat(listUnhealthySubscriptionsForOwnerAsPlainText("Team A")).isEmpty();
    }

    @Test
    public void shouldReturnOnlyUnhealthySubscriptionOfSingleOwner() {
        // given
        Topic topic = operations.buildTopic(randomTopic("group", "topic").build());
        createSubscriptionForOwner(topic, "ownedSubscription1", "Team A");
        final Subscription subscription = createSubscriptionForOwner(topic, "ownedSubscription2", "Team A");
        createSubscriptionForOwner(topic, "ownedSubscription3", "Team B");
        PrometheusTopicResponse topicStub = new PrometheusTopicResponse(100, 50, 0);
        prometheusEndpoint.returnTopicMetrics(topic, topicStub);
        prometheusEndpoint.returnSubscriptionMetrics(topic, "ownedSubscription1",
                builder().withRate(100).withRatedStatusCode("500", 0).build());
        prometheusEndpoint.returnSubscriptionMetrics(topic, "ownedSubscription2",
                builder().withRate(50).withRatedStatusCode("500", 11).build());
        prometheusEndpoint.returnSubscriptionMetrics(topic, "ownedSubscription3",
                builder().withRate(100).withRatedStatusCode("500", 0).build());

        // then
        assertThat(listUnhealthySubscriptionsForOwner("Team A")).containsOnly(
                new UnhealthySubscription("ownedSubscription2", topic.getQualifiedName(), Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription.getQualifiedName().toString())))
        );
        assertThat(listUnhealthySubscriptionsForOwnerAsPlainText("Team A")).isEqualTo(
                "ownedSubscription2 - Consuming service returns a lot of 5xx codes for subscription "
                        + subscription.getQualifiedName().toString() + ", currently 11 5xx/s"
        );
    }

    @Test
    public void shouldReturnOnlySpecifiedUnhealthySubscription() {
        // given
        Topic topic1 = operations.buildTopic(topic("group", "topic1").build());
        Topic topic2 = operations.buildTopic(topic("group", "topic2").build());
        Topic topic3 = operations.buildTopic(topic("group", "topic3").build());

        createSubscriptionForOwner(topic1, "ownedSubscription1", "Team A");
        final Subscription subscription2 = createSubscriptionForOwner(topic2, "ownedSubscription2", "Team A");
        final Subscription subscription3 = createSubscriptionForOwner(topic3, "ownedSubscription3", "Team A");
        createSubscriptionForOwner(topic1, "ownedSubscription4", "Team B");
        final Subscription subscription5 = createSubscriptionForOwner(topic2, "ownedSubscription5", "Team B");
        final Subscription subscription6 = createSubscriptionForOwner(topic3, "ownedSubscription6", "Team B");

        prometheusEndpoint.returnTopicMetrics(topic1, new PrometheusTopicResponse(100, 50, 0));
        prometheusEndpoint.returnTopicMetrics(topic2, new PrometheusTopicResponse(100, 50, 0));
        prometheusEndpoint.returnTopicMetrics(topic3, new PrometheusTopicResponse(100, 50, 0));

        prometheusEndpoint.returnSubscriptionMetrics(topic1, "ownedSubscription1",
                builder().withRate(100).withRatedStatusCode("500", 0).build());
        prometheusEndpoint.returnSubscriptionMetrics(topic2, "ownedSubscription2",
                builder().withRate(50).withRatedStatusCode("500", 11).build());
        prometheusEndpoint.returnSubscriptionMetrics(topic3, "ownedSubscription3",
                builder().withRate(50).withRatedStatusCode("500", 11).build());
        prometheusEndpoint.returnSubscriptionMetrics(topic1, "ownedSubscription4",
                builder().withRate(100).withRatedStatusCode("500", 0).build());
        prometheusEndpoint.returnSubscriptionMetrics(topic2, "ownedSubscription5",
                builder().withRate(50).withRatedStatusCode("500", 11).build());
        prometheusEndpoint.returnSubscriptionMetrics(topic3, "ownedSubscription6",
                builder().withRate(50).withRatedStatusCode("500", 11).build());

        // then
        assertThat(listUnhealthySubscriptionsForOwner("Team A", Lists.asList(), Lists.asList("group.topic2"))).containsOnly(
                new UnhealthySubscription("ownedSubscription2", "group.topic2", Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription2.getQualifiedName().toString())))
        );
        assertThat(listUnhealthySubscriptionsForOwnerAsPlainText("Team A", Lists.asList(), Lists.asList("group.topic2"))).isEqualTo(
                "ownedSubscription2 - Consuming service returns a lot of 5xx codes for subscription "
                        + subscription2.getQualifiedName().toString() + ", currently 11 5xx/s"
        );
        assertThat(listAllUnhealthySubscriptions(Lists.asList(), Lists.asList("group.topic2"))).containsOnly(
                new UnhealthySubscription("ownedSubscription2", "group.topic2", Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription2.getQualifiedName().toString()))),
                new UnhealthySubscription("ownedSubscription5", "group.topic2", Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription5.getQualifiedName().toString())))
        );
        assertThat(listAllUnhealthySubscriptionsAsPlainText(Lists.asList(), Lists.asList("group.topic2"))).isEqualTo(
                "ownedSubscription2 - Consuming service returns a lot of 5xx codes for subscription "
                        + subscription2.getQualifiedName().toString() + ", currently 11 5xx/s\r\n"
                        + "ownedSubscription5 - Consuming service returns a lot of 5xx codes for subscription "
                        + subscription5.getQualifiedName().toString() + ", currently 11 5xx/s"
        );
        assertThat(listAllUnhealthySubscriptionsAsPlainText(Lists.asList("ownedSubscription2"), Lists.asList("group.topic2"))).isEqualTo(
                "ownedSubscription2 - Consuming service returns a lot of 5xx codes for subscription "
                        + subscription2.getQualifiedName().toString() + ", currently 11 5xx/s"
        );
        assertThat(listAllUnhealthySubscriptions()).containsOnly(
                new UnhealthySubscription("ownedSubscription2", "group.topic2", Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription2.getQualifiedName().toString()))),
                new UnhealthySubscription("ownedSubscription3", "group.topic3", Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription3.getQualifiedName().toString()))),
                new UnhealthySubscription("ownedSubscription5", "group.topic2", Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription5.getQualifiedName().toString()))),
                new UnhealthySubscription("ownedSubscription6", "group.topic3", Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription6.getQualifiedName().toString())))
        );
        assertThat(listAllUnhealthySubscriptions(Lists.asList("ownedSubscription2", "ownedSubscription3"),
                Lists.asList("group.topic2", "group.topic3"))).containsOnly(
                new UnhealthySubscription("ownedSubscription2", "group.topic2", Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription2.getQualifiedName().toString()))),
                new UnhealthySubscription("ownedSubscription3", "group.topic3", Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription3.getQualifiedName().toString())))
        );
    }

    @Test
    public void shouldReportAllUnhealthySubscriptionsForEmptyOwnerSource() {
        // given
        Topic topic = operations.buildTopic(randomTopic("group", "topic").build());
        createSubscriptionForOwner(topic, "ownedSubscription1", "Team A");
        final Subscription subscription = createSubscriptionForOwner(topic, "ownedSubscription2", "Team A");

        prometheusEndpoint.returnTopicMetrics(topic, new PrometheusTopicResponse(100, 50, 0));
        prometheusEndpoint.returnSubscriptionMetrics(topic, "ownedSubscription1",
                builder().withRate(100).withRatedStatusCode("500", 0).build());
        prometheusEndpoint.returnSubscriptionMetrics(topic, "ownedSubscription2",
                builder().withRate(50).withRatedStatusCode("500", 11).build());

        // then
        assertThat(listAllUnhealthySubscriptions()).containsOnly(
                new UnhealthySubscription("ownedSubscription2", topic.getQualifiedName(), Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription.getQualifiedName().toString())))
        );
        assertThat(listAllUnhealthySubscriptionsAsPlainText()).isEqualTo(
                "ownedSubscription2 - Consuming service returns a lot of 5xx codes for subscription "
                        + subscription.getQualifiedName().toString() + ", currently 11 5xx/s"
        );
    }

    @Test
    public void shouldReportUnhealthySubscriptionsDisrespectingSeverity() {
        // given
        Topic topic = operations.buildTopic(randomTopic("group", "topic").build());
        final Subscription subscription1 = createSubscriptionForOwner(topic, "ownedSubscription1", "Team A", Severity.CRITICAL);
        final Subscription subscription2 = createSubscriptionForOwner(topic, "ownedSubscription2", "Team A", Severity.IMPORTANT);
        final Subscription subscription3 = createSubscriptionForOwner(topic, "ownedSubscription3", "Team A", Severity.NON_IMPORTANT);

        prometheusEndpoint.returnTopicMetrics(topic, new PrometheusTopicResponse(100, 50, 0));
        prometheusEndpoint.returnSubscriptionMetrics(topic, "ownedSubscription1",
                builder().withRate(50).withRatedStatusCode("500", 11).build());
        prometheusEndpoint.returnSubscriptionMetrics(topic, "ownedSubscription2",
                builder().withRate(50).withRatedStatusCode("500", 11).build());
        prometheusEndpoint.returnSubscriptionMetrics(topic, "ownedSubscription3",
                builder().withRate(50).withRatedStatusCode("500", 11).build());

        // then
        assertThat(listUnhealthySubscriptionsDisrespectingSeverity("Team A")).contains(
                new UnhealthySubscription("ownedSubscription1", topic.getQualifiedName(), Severity.CRITICAL,
                        ImmutableSet.of(malfunctioning(11, subscription1.getQualifiedName().toString()))),
                new UnhealthySubscription("ownedSubscription2", topic.getQualifiedName(), Severity.IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription2.getQualifiedName().toString()))),
                new UnhealthySubscription("ownedSubscription3", topic.getQualifiedName(), Severity.NON_IMPORTANT,
                        ImmutableSet.of(malfunctioning(11, subscription3.getQualifiedName().toString())))
        );
        assertThat(listUnhealthySubscriptionsDisrespectingSeverityAsPlainText("Team A")).isEqualTo(
                "ownedSubscription1 - Consuming service returns a lot of 5xx codes for subscription "
                        + subscription1.getQualifiedName().toString() + ", currently 11 5xx/s\r\n"
                        + "ownedSubscription2 - Consuming service returns a lot of 5xx codes for subscription "
                        + subscription2.getQualifiedName().toString() + ", currently 11 5xx/s\r\n"
                        + "ownedSubscription3 - Consuming service returns a lot of 5xx codes for subscription "
                        + subscription3.getQualifiedName().toString() + ", currently 11 5xx/s"
        );
    }

    @Test
    public void shouldTimeoutUnhealthySubscriptionsRequest() {
        // given
        Topic topic = operations.buildTopic(randomTopic("group", "topic").build());
        createSubscriptionForOwner(topic, "ownedSubscription1", "Team A", Severity.CRITICAL);
        int prometheusDelay = 1000;

        prometheusEndpoint.returnSubscriptionMetricsWithDelay(topic, "ownedSubscription1",
                builder().withRate(50).withRatedStatusCode("500", 11).build(), prometheusDelay);

        // when
        long start = System.currentTimeMillis();
        Throwable thrown = catchThrowable(() -> listUnhealthyAsPlainText("Plaintext", "Team A", true, Lists.asList(), Lists.asList()));
        long end = System.currentTimeMillis();

        // then
        assertThat(thrown).isInstanceOf(InternalServerErrorException.class);
        assertThat(end - start < prometheusDelay);

    }


    @Test
    public void shouldReportSuspendedSubscriptionAsHealthy() {
        // given
        Topic topic = operations.buildTopic(randomTopic("group", "topic").build());
        Subscription s = createSubscriptionForOwner(topic, "subscription1", "Team A");

        // when
        s.setState(Subscription.State.SUSPENDED);

        // then
        assertThat(listUnhealthySubscriptionsForOwner("Team A")).isEmpty();
        assertThat(listUnhealthySubscriptionsForOwnerAsPlainText("Team A")).isEmpty();
    }

    private Subscription createSubscriptionForOwner(Topic topic, String subscriptionName, String ownerId) {
        return createSubscriptionForOwner(topic, subscriptionName, ownerId, Severity.IMPORTANT);
    }

    private Subscription createSubscriptionForOwner(Topic topic, String subscriptionName, String ownerId, Severity severity) {
        Subscription subscription = subscription(topic, subscriptionName)
                .withEndpoint(HTTP_ENDPOINT_URL)
                .withOwner(ownerId(ownerId))
                .withMonitoringDetails(new MonitoringDetails(severity, ""))
                .build();

        operations.createSubscription(topic, subscription);
        return subscription;
    }

    private OwnerId ownerId(String ownerId) {
        return new OwnerId("Plaintext", ownerId);
    }

    private List<UnhealthySubscription> listUnhealthySubscriptionsForOwner(String ownerId) {
        return listUnhealthy("Plaintext", ownerId, true, Lists.asList(), Lists.asList());
    }

    private List<UnhealthySubscription> listUnhealthySubscriptionsForOwner(String ownerId, List<String> subscriptionNames,
                                                                           List<String> qualifiedTopicNames) {
        return listUnhealthy("Plaintext", ownerId, true, subscriptionNames, qualifiedTopicNames);
    }

    private List<UnhealthySubscription> listUnhealthySubscriptionsDisrespectingSeverity(String ownerId) {
        return listUnhealthy("Plaintext", ownerId, false, Lists.asList(), Lists.asList());
    }

    private List<UnhealthySubscription> listUnhealthySubscriptionsDisrespectingSeverity(String ownerId, List<String> subscriptionNames,
                                                                                        List<String> qualifiedTopicNames) {
        return listUnhealthy("Plaintext", ownerId, false, subscriptionNames, qualifiedTopicNames);
    }

    private List<UnhealthySubscription> listAllUnhealthySubscriptions() {
        return listUnhealthy(null, null, true, Lists.asList(), Lists.asList());
    }

    private List<UnhealthySubscription> listAllUnhealthySubscriptions(List<String> subscriptionNames, List<String> qualifiedTopicNames) {
        return listUnhealthy(null, null, true, subscriptionNames, qualifiedTopicNames);
    }

    private List<UnhealthySubscription> listUnhealthy(String ownerSourceName, String ownerId, boolean respectMonitoringSeverity,
                                                      List<String> subscriptionNames, List<String> qualifiedTopicNames) {
        return management.unhealthyEndpoint()
                .listUnhealthy(ownerSourceName, ownerId, respectMonitoringSeverity, subscriptionNames, qualifiedTopicNames)
                .readEntity(new GenericType<>() {
                });
    }

    private String listUnhealthySubscriptionsForOwnerAsPlainText(String ownerId) {
        return listUnhealthyAsPlainText("Plaintext", ownerId, true, Lists.asList(), Lists.asList());
    }

    private String listUnhealthySubscriptionsForOwnerAsPlainText(String ownerId, List<String> subscriptionNames,
                                                                 List<String> qualifiedTopicNames) {
        return listUnhealthyAsPlainText("Plaintext", ownerId, true, subscriptionNames, qualifiedTopicNames);
    }

    private String listUnhealthySubscriptionsDisrespectingSeverityAsPlainText(String ownerId) {
        return listUnhealthyAsPlainText("Plaintext", ownerId, false, Lists.asList(), Lists.asList());
    }

    private String listAllUnhealthySubscriptionsAsPlainText() {
        return listUnhealthyAsPlainText(null, null, true, Lists.asList(), Lists.asList());
    }

    private String listAllUnhealthySubscriptionsAsPlainText(List<String> subscriptionNames, List<String> qualifiedTopicNames) {
        return listUnhealthyAsPlainText(null, null, true, subscriptionNames, qualifiedTopicNames);
    }

    private String listUnhealthyAsPlainText(String ownerSourceName, String ownerId, boolean respectMonitoringSeverity,
                                            List<String> subscriptionNames, List<String> qualifiedTopicNames) {
        return httpClient.target(MANAGEMENT_ENDPOINT_URL)
                .path("unhealthy")
                .queryParam("ownerSourceName", ownerSourceName)
                .queryParam("ownerId", ownerId)
                .queryParam("respectMonitoringSeverity", respectMonitoringSeverity)
                .queryParam("subscriptionNames", (Object[]) subscriptionNames.toArray(new String[0]))
                .queryParam("qualifiedTopicNames", (Object[]) qualifiedTopicNames.toArray(new String[0]))
                .request(TEXT_PLAIN)
                .get(String.class);
    }
}
