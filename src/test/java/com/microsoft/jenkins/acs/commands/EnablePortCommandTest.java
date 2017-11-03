package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.Page;
import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.network.LoadBalancer;
import com.microsoft.azure.management.network.LoadBalancerBackend;
import com.microsoft.azure.management.network.LoadBalancerFrontend;
import com.microsoft.azure.management.network.LoadBalancerHttpProbe;
import com.microsoft.azure.management.network.LoadBalancerInboundNatPool;
import com.microsoft.azure.management.network.LoadBalancerInboundNatRule;
import com.microsoft.azure.management.network.LoadBalancerPrivateFrontend;
import com.microsoft.azure.management.network.LoadBalancerPublicFrontend;
import com.microsoft.azure.management.network.LoadBalancerTcpProbe;
import com.microsoft.azure.management.network.LoadBalancers;
import com.microsoft.azure.management.network.LoadBalancingRule;
import com.microsoft.azure.management.network.LoadDistribution;
import com.microsoft.azure.management.network.Network;
import com.microsoft.azure.management.network.NetworkSecurityGroup;
import com.microsoft.azure.management.network.NetworkSecurityGroups;
import com.microsoft.azure.management.network.NetworkSecurityRule;
import com.microsoft.azure.management.network.Protocol;
import com.microsoft.azure.management.network.PublicIPAddress;
import com.microsoft.azure.management.network.SecurityRuleAccess;
import com.microsoft.azure.management.network.SecurityRuleDirection;
import com.microsoft.azure.management.network.Subnet;
import com.microsoft.azure.management.network.TransportProtocol;
import com.microsoft.azure.management.network.model.HasNetworkInterfaces;
import com.microsoft.jenkins.acs.orchestrators.ServicePort;
import com.microsoft.rest.RestException;
import com.microsoft.rest.ServiceCallback;
import com.microsoft.rest.ServiceFuture;
import org.junit.Assert;
import org.junit.Test;
import rx.Observable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EnablePortCommandTest {

    private NetworkSecurityRule mockNetworkSecurityRule(final int priority, final String destinationPortRange,
                                                        SecurityRuleAccess access, SecurityRuleDirection direction) {
        final NetworkSecurityRule rule = mock(NetworkSecurityRule.class);
        when(rule.destinationPortRange()).thenReturn(destinationPortRange);
        when(rule.priority()).thenReturn(priority);
        when(rule.access()).thenReturn(access);
        when(rule.direction()).thenReturn(direction);
        return rule;
    }

    @Test
    public void filterPortsToOpen_RuleAllowAll() throws EnablePortCommand.InvalidConfigException {
        final Collection<NetworkSecurityRule> rules = Collections.singletonList(
                mockNetworkSecurityRule(10, "*", SecurityRuleAccess.ALLOW, SecurityRuleDirection.INBOUND)
        );
        final Set<Integer> portsToOpen = new HashSet<>(Collections.singletonList(8080));

        final int maxPriority = EnablePortCommand.filterPortsToOpen(rules, portsToOpen, System.out);
        Assert.assertEquals(10, maxPriority);
        Assert.assertTrue(portsToOpen.isEmpty());
    }

    @Test
    public void filterPortsToOpen_RuleRange() throws EnablePortCommand.InvalidConfigException {
        final Collection<NetworkSecurityRule> rules = Collections.singletonList(
                mockNetworkSecurityRule(10, "8000-9000", SecurityRuleAccess.ALLOW, SecurityRuleDirection.INBOUND)
        );
        final Set<Integer> portsToOpen = new HashSet<>(Arrays.asList(8080, 9090));

        final int maxPriority = EnablePortCommand.filterPortsToOpen(rules, portsToOpen, System.out);
        Assert.assertEquals(10, maxPriority);
        Assert.assertEquals(new HashSet<>(Collections.singletonList(9090)), portsToOpen);
    }

    @Test
    public void filterPortsToOpen_RuleRangeInvalidNumberFormat() throws EnablePortCommand.InvalidConfigException {
        final Collection<NetworkSecurityRule> rules = Collections.singletonList(
                mockNetworkSecurityRule(10, "8081-xx", SecurityRuleAccess.ALLOW, SecurityRuleDirection.INBOUND)
        );
        final Set<Integer> portsToOpen = new HashSet<>(Collections.singletonList(8080));

        try {
            final int maxPriority = EnablePortCommand.filterPortsToOpen(rules, portsToOpen, System.out);
            Assert.fail("Should throw InvalidConfigException");
        } catch (EnablePortCommand.InvalidConfigException e) {
            // Should throw
        }
    }

    @Test
    public void filterPortsToOpen_RuleSingle() throws EnablePortCommand.InvalidConfigException {
        final Collection<NetworkSecurityRule> rules = Arrays.asList(
                mockNetworkSecurityRule(20, "8080", SecurityRuleAccess.ALLOW, SecurityRuleDirection.INBOUND),
                mockNetworkSecurityRule(10, "8081", SecurityRuleAccess.ALLOW, SecurityRuleDirection.INBOUND)
        );
        final Set<Integer> portsToOpen = new HashSet<>(Arrays.asList(8080, 8081, 8082));

        final int maxPriority = EnablePortCommand.filterPortsToOpen(rules, portsToOpen, System.out);
        Assert.assertEquals(20, maxPriority);
        Assert.assertEquals(new HashSet<>(Collections.singletonList(8082)), portsToOpen);
    }

    @Test
    public void filterPortsToOpen_RuleDeny() throws EnablePortCommand.InvalidConfigException {
        final Collection<NetworkSecurityRule> rules = Collections.singletonList(
                mockNetworkSecurityRule(10, "8080", SecurityRuleAccess.DENY, SecurityRuleDirection.INBOUND)
        );
        final Set<Integer> portsToOpen = new HashSet<>(Collections.singletonList(8080));

        final int maxPriority = EnablePortCommand.filterPortsToOpen(rules, portsToOpen, System.out);
        Assert.assertEquals(10, maxPriority);
        Assert.assertEquals(new HashSet<>(Collections.singletonList(8080)), portsToOpen);
    }

    @Test
    public void filterPortsToOpen_RuleOutbound() throws EnablePortCommand.InvalidConfigException {
        final Collection<NetworkSecurityRule> rules = Collections.singletonList(
                mockNetworkSecurityRule(10, "8080", SecurityRuleAccess.ALLOW, SecurityRuleDirection.OUTBOUND)
        );
        final Set<Integer> portsToOpen = new HashSet<>(Collections.singletonList(8080));

        final int maxPriority = EnablePortCommand.filterPortsToOpen(rules, portsToOpen, System.out);
        Assert.assertEquals(10, maxPriority);
        Assert.assertEquals(new HashSet<>(Collections.singletonList(8080)), portsToOpen);
    }

    private NetworkSecurityGroup mockNetworkSecurityGroup(String name, Map<String, NetworkSecurityRule> rulesSet) {
        final NetworkSecurityGroup nsg = mock(NetworkSecurityGroup.class);
        when(nsg.name()).thenReturn(name);
        when(nsg.securityRules()).thenReturn(rulesSet);

        final NetworkSecurityGroup.Update update = mock(NetworkSecurityGroup.Update.class, RETURNS_DEEP_STUBS);
        when(nsg.update()).thenReturn(update);

        return nsg;
    }

    private Azure mockAzureClientWithNetworkSecurityGroups(List<NetworkSecurityGroup> nsgList) {
        final PagedList<NetworkSecurityGroup> nsgs = new PagedList<NetworkSecurityGroup>() {
            @Override
            public Page<NetworkSecurityGroup> nextPage(String s) throws RestException, IOException {
                return null;
            }
        };
        nsgs.addAll(nsgList);

        final NetworkSecurityGroups nsgsMgr = mock(NetworkSecurityGroups.class);
        when(nsgsMgr.listByResourceGroup(anyString())).thenReturn(nsgs);

        final Azure azureClient = mock(Azure.class);
        when(azureClient.networkSecurityGroups()).thenReturn(nsgsMgr);

        return azureClient;
    }

    @Test
    public void createSecurityRules() throws IOException, EnablePortCommand.InvalidConfigException {
        final Map<String, NetworkSecurityRule> rulesSet = new HashMap<>();
        rulesSet.put("rule1", mockNetworkSecurityRule(10, "8080", SecurityRuleAccess.ALLOW, SecurityRuleDirection.INBOUND));
        rulesSet.put("rule2", mockNetworkSecurityRule(20, "8081", SecurityRuleAccess.ALLOW, SecurityRuleDirection.INBOUND));

        final NetworkSecurityGroup nsg = mockNetworkSecurityGroup("dcos-agent-public-nsg-xxx", rulesSet);
        final Azure azureClient = mockAzureClientWithNetworkSecurityGroups(Collections.singletonList(nsg));

        final List<ServicePort> servicePorts = Arrays.asList(
                new ServicePort(8080, 8080, Protocol.TCP),
                new ServicePort(8081, 8081, Protocol.TCP),
                new ServicePort(8082, 8082, Protocol.TCP),
                new ServicePort(8083, 8083, Protocol.TCP)
        );

        EnablePortCommand.createSecurityRules(
                azureClient,
                "resource-group",
                "dcos",
                servicePorts,
                System.out
        );

        final NetworkSecurityGroup.Update update = nsg.update();

        verify(update
                .defineRule("Allow_" + 8082)
                .allowInbound()
                .fromAddress("Internet")
                .fromAnyPort()
                .toAnyAddress()
                .toPort(8082)
                .withAnyProtocol()
                .withDescription(anyString())
                .withPriority(30)
        ).attach();

        verify(update
                .defineRule("Allow_" + 8083)
                .allowInbound()
                .fromAddress("Internet")
                .fromAnyPort()
                .toAnyAddress()
                .toPort(8083)
                .withAnyProtocol()
                .withDescription(anyString())
                .withPriority(40)
        ).attach();
    }

    private LoadBalancingRule mockLoadBalancingRule(String name, int frontendPort, TransportProtocol protocol) {
        final LoadBalancingRule rule = mock(LoadBalancingRule.class);

        when(rule.name()).thenReturn(name);
        when(rule.frontendPort()).thenReturn(frontendPort);
        when(rule.protocol()).thenReturn(protocol);

        return rule;
    }

    private LoadBalancer mockLoadBalancer(String name,
                                          Map<String, LoadBalancerBackend> backends,
                                          Map<String, LoadBalancerFrontend> frontends,
                                          Map<String, LoadBalancingRule> rulesSet) {
        final LoadBalancer lb = mock(LoadBalancer.class);
        when(lb.name()).thenReturn(name);
        when(lb.backends()).thenReturn(backends);
        when(lb.frontends()).thenReturn(frontends);
        when(lb.loadBalancingRules()).thenReturn(rulesSet);

        return lb;
    }

    private Azure mockAzureClientWithLoadBalancers(List<LoadBalancer> lbList) {
        final PagedList<LoadBalancer> lbs = new PagedList<LoadBalancer>() {
            @Override
            public Page<LoadBalancer> nextPage(String s) throws RestException, IOException {
                return null;
            }
        };
        lbs.addAll(lbList);

        final LoadBalancers lbsMgr = mock(LoadBalancers.class);
        when(lbsMgr.listByResourceGroup(anyString())).thenReturn(lbs);

        final Azure azureClient = mock(Azure.class);
        when(azureClient.loadBalancers()).thenReturn(lbsMgr);

        return azureClient;
    }

    @Test
    public void createLoadBalancerRules() throws IOException, EnablePortCommand.InvalidConfigException {
        final Map<String, LoadBalancerBackend> backends = new HashMap<>();
        final LoadBalancerBackend backend = mock(LoadBalancerBackend.class);
        when(backend.name()).thenReturn("backend");
        backends.put("backend", backend);

        final Map<String, LoadBalancerFrontend> frontends = new HashMap<>();
        final LoadBalancerFrontend frontend = mock(LoadBalancerFrontend.class);
        when(frontend.name()).thenReturn("frontend");
        frontends.put("frontend", frontend);

        final Map<String, LoadBalancingRule> rulesSet = new HashMap<>();
        rulesSet.put("rule1", mockLoadBalancingRule("rule1", 8080, TransportProtocol.TCP));
        rulesSet.put("rule2", mockLoadBalancingRule("rule2", 8081, TransportProtocol.UDP));

        final LoadBalancer lb = mockLoadBalancer("dcos-agent-lb-xxx", backends, frontends, rulesSet);

        // Mockito has some issues for this kind of builder pattern so we mock manually
        final MockLoadBalancerUpdate update = new MockLoadBalancerUpdate();
        when(lb.update()).thenReturn(update);

        final Azure azureClient = mockAzureClientWithLoadBalancers(Collections.singletonList(lb));

        final List<ServicePort> servicePorts = Arrays.asList(
                new ServicePort(8080, 8080, Protocol.TCP),
                new ServicePort(8081, 8081, Protocol.TCP),
                new ServicePort(8082, 8082, Protocol.TCP)
        );

        EnablePortCommand.createLoadBalancerRules(
                azureClient,
                "resource-group",
                "dcos",
                servicePorts,
                System.out
        );

        Assert.assertTrue(update.isApplied);
        Assert.assertEquals(2, update.tcpProbes.size());

        Assert.assertEquals("tcpPort8081Probe", update.tcpProbes.get(0).name);
        Assert.assertEquals(8081, update.tcpProbes.get(0).port);
        Assert.assertEquals("tcpPort8082Probe", update.tcpProbes.get(1).name);
        Assert.assertEquals(8082, update.tcpProbes.get(1).port);

        Assert.assertEquals(2, update.rules.size());

        Assert.assertEquals("JLBRuleTCP8081", update.rules.get(0).name);
        Assert.assertEquals(TransportProtocol.TCP, update.rules.get(0).protocol);
        Assert.assertEquals("frontend", update.rules.get(0).frontend);
        Assert.assertEquals(8081, update.rules.get(0).frontendPort);
        Assert.assertEquals("backend", update.rules.get(0).backend);
        Assert.assertEquals(8081, update.rules.get(0).backendPort);
        Assert.assertEquals(EnablePortCommand.LOAD_BALANCER_IDLE_TIMEOUT_IN_MINUTES,
                update.rules.get(0).idleTimeoutInMinutes);
        Assert.assertEquals(LoadDistribution.DEFAULT, update.rules.get(0).loadDistribution);

        Assert.assertEquals("JLBRuleTCP8082", update.rules.get(1).name);
        Assert.assertEquals(TransportProtocol.TCP, update.rules.get(1).protocol);
        Assert.assertEquals("frontend", update.rules.get(1).frontend);
        Assert.assertEquals(8082, update.rules.get(1).frontendPort);
        Assert.assertEquals("backend", update.rules.get(1).backend);
        Assert.assertEquals(8082, update.rules.get(1).backendPort);
        Assert.assertEquals(EnablePortCommand.LOAD_BALANCER_IDLE_TIMEOUT_IN_MINUTES,
                update.rules.get(1).idleTimeoutInMinutes);
        Assert.assertEquals(LoadDistribution.DEFAULT, update.rules.get(1).loadDistribution);
    }

    @Test
    public void createLoadBalancerRules_MissMatchBackendFrontend() throws IOException {
        final Map<String, LoadBalancerBackend> backends = new HashMap<>();
        final Map<String, LoadBalancerFrontend> frontends = new HashMap<>();
        final Map<String, LoadBalancingRule> rulesSet = new HashMap<>();
        final LoadBalancer lb = mockLoadBalancer("dcos-agent-lb-xxx", backends, frontends, rulesSet);

        // Mockito has some issues for this kind of builder pattern so we mock manually
        final MockLoadBalancerUpdate update = new MockLoadBalancerUpdate();
        when(lb.update()).thenReturn(update);

        final Azure azureClient = mockAzureClientWithLoadBalancers(Collections.singletonList(lb));

        final List<ServicePort> servicePorts = Arrays.asList(
                new ServicePort(8080, 8080, Protocol.TCP),
                new ServicePort(8081, 8081, Protocol.TCP),
                new ServicePort(8082, 8082, Protocol.TCP)
        );

        try {
            EnablePortCommand.createLoadBalancerRules(
                    azureClient,
                    "resource-group",
                    "dcos",
                    servicePorts,
                    System.out);
            Assert.fail("Should throw InvalidConfigException");
        } catch (EnablePortCommand.InvalidConfigException ex) {
            Assert.assertFalse(update.isApplied);
            Assert.assertTrue(update.tcpProbes.isEmpty());
            Assert.assertTrue(update.rules.isEmpty());
        }
    }

    private static final class MockLoadBalancerTcpProbe implements
            LoadBalancerTcpProbe.UpdateDefinitionStages.Blank<LoadBalancer.Update>,
            LoadBalancerTcpProbe.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> {

        private final MockLoadBalancerUpdate update;
        public final String name;
        public int port;

        MockLoadBalancerTcpProbe(MockLoadBalancerUpdate update, String name) {
            this.update = update;
            this.name = name;
        }

        @Override
        public LoadBalancerTcpProbe.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> withPort(int port) {
            this.port = port;
            return this;
        }

        @Override
        public LoadBalancerTcpProbe.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> withIntervalInSeconds(int seconds) {
            Assert.fail("Should not reach");
            return this;
        }

        @Override
        public LoadBalancerTcpProbe.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> withNumberOfProbes(int probes) {
            Assert.fail("Should not reach");
            return this;
        }

        @Override
        public LoadBalancer.Update attach() {
            this.update.tcpProbes.add(this);
            return this.update;
        }
    }

    private static final class MockLoadBalancingRule implements
            LoadBalancingRule.UpdateDefinitionStages.Blank<LoadBalancer.Update>,
            LoadBalancingRule.UpdateDefinitionStages.WithFrontend<LoadBalancer.Update>,
            LoadBalancingRule.UpdateDefinitionStages.WithFrontendPort<LoadBalancer.Update>,
            LoadBalancingRule.UpdateDefinitionStages.WithProbe<LoadBalancer.Update>,
            LoadBalancingRule.UpdateDefinitionStages.WithBackend<LoadBalancer.Update>,
            LoadBalancingRule.UpdateDefinitionStages.WithBackendPort<LoadBalancer.Update>,
            LoadBalancingRule.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> {

        private final MockLoadBalancerUpdate update;
        public final String name;
        public int idleTimeoutInMinutes;
        public LoadDistribution loadDistribution;
        public String backend;
        public int backendPort;
        public String frontend;
        public int frontendPort;
        public String probe;
        public TransportProtocol protocol;

        MockLoadBalancingRule(MockLoadBalancerUpdate update, String name) {
            this.update = update;
            this.name = name;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithFrontend<LoadBalancer.Update> withProtocol(TransportProtocol protocol) {
            this.protocol = protocol;
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithBackendPort<LoadBalancer.Update> toBackend(String backendName) {
            this.backend = backendName;
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> withProbe(String name) {
            this.probe = name;
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithBackendPort<LoadBalancer.Update> toExistingVirtualMachines(HasNetworkInterfaces... vms) {
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithBackendPort<LoadBalancer.Update> toExistingVirtualMachines(Collection<HasNetworkInterfaces> vms) {
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> toBackendPort(int port) {
            this.backendPort = port;
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithFrontendPort<LoadBalancer.Update> fromFrontend(String frontendName) {
            this.frontend = frontendName;
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithFrontendPort<LoadBalancer.Update> fromExistingPublicIPAddress(PublicIPAddress publicIPAddress) {
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithFrontendPort<LoadBalancer.Update> fromExistingPublicIPAddress(String resourceId) {
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithFrontendPort<LoadBalancer.Update> fromExistingSubnet(Network network, String subnetName) {
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithFrontendPort<LoadBalancer.Update> fromExistingSubnet(String networkResourceId, String subnetName) {
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithFrontendPort<LoadBalancer.Update> fromExistingSubnet(Subnet subnet) {
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithBackend<LoadBalancer.Update> fromFrontendPort(int port) {
            this.frontendPort = port;
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> withIdleTimeoutInMinutes(int minutes) {
            this.idleTimeoutInMinutes = minutes;
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> withLoadDistribution(LoadDistribution loadDistribution) {
            this.loadDistribution = loadDistribution;
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> withFloatingIPEnabled() {
            Assert.fail("Should not reach");
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> withFloatingIPDisabled() {
            Assert.fail("Should not reach");
            return this;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.WithAttach<LoadBalancer.Update> withFloatingIP(boolean enabled) {
            Assert.fail("Should not reach");
            return this;
        }

        @Override
        public LoadBalancer.Update attach() {
            this.update.rules.add(this);
            return this.update;
        }
    }

    private static final class MockLoadBalancerUpdate implements LoadBalancer.Update {

        public boolean isApplied;
        public final List<MockLoadBalancerTcpProbe> tcpProbes = new ArrayList<>();
        public final List<MockLoadBalancingRule> rules = new ArrayList<>();

        @Override
        public LoadBalancer.Update withoutBackend(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerBackend.UpdateDefinitionStages.Blank<LoadBalancer.Update> defineBackend(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerBackend.Update updateBackend(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancer.Update withoutInboundNatPool(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerInboundNatPool.UpdateDefinitionStages.Blank<LoadBalancer.Update> defineInboundNatPool(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerInboundNatPool.Update updateInboundNatPool(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancer.Update withoutInboundNatRule(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerInboundNatRule.UpdateDefinitionStages.Blank<LoadBalancer.Update> defineInboundNatRule(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerInboundNatRule.Update updateInboundNatRule(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerPrivateFrontend.UpdateDefinitionStages.Blank<LoadBalancer.Update> definePrivateFrontend(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerPublicFrontend.UpdateDefinitionStages.Blank<LoadBalancer.Update> definePublicFrontend(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancer.Update withoutFrontend(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerPrivateFrontend.Update updatePrivateFrontend(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerPublicFrontend.Update updatePublicFrontend(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancingRule.UpdateDefinitionStages.Blank<LoadBalancer.Update> defineLoadBalancingRule(String name) {
            return new MockLoadBalancingRule(this, name);
        }

        @Override
        public LoadBalancer.Update withoutLoadBalancingRule(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancingRule.Update updateLoadBalancingRule(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerHttpProbe.UpdateDefinitionStages.Blank<LoadBalancer.Update> defineHttpProbe(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerTcpProbe.UpdateDefinitionStages.Blank<LoadBalancer.Update> defineTcpProbe(String name) {
            return new MockLoadBalancerTcpProbe(this, name);
        }

        @Override
        public LoadBalancer.Update withoutProbe(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerTcpProbe.Update updateTcpProbe(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancerHttpProbe.Update updateHttpProbe(String name) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancer.Update withTags(Map<String, String> tags) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancer.Update withTag(String key, String value) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancer.Update withoutTag(String key) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public LoadBalancer apply() {
            if (this.isApplied) {
                Assert.fail("Should apply only once");
            } else {
                this.isApplied = true;
            }
            return null;
        }

        @Override
        public Observable<LoadBalancer> applyAsync() {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public ServiceFuture<LoadBalancer> applyAsync(ServiceCallback<LoadBalancer> callback) {
            Assert.fail("Should not reach");
            return null;
        }

        @Override
        public String key() {
            Assert.fail("Should not reach");
            return null;
        }
    }
}
