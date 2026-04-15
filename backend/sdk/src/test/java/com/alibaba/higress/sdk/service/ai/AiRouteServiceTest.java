/*
 * Copyright (c) 2022-2023 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.higress.sdk.service.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.alibaba.higress.sdk.constant.KubernetesConstants;
import com.alibaba.higress.sdk.constant.plugin.BuiltInPluginName;
import com.alibaba.higress.sdk.model.WasmPluginInstance;
import com.alibaba.higress.sdk.model.WasmPluginInstanceScope;
import com.alibaba.higress.sdk.model.ai.AiRoute;
import com.alibaba.higress.sdk.model.ai.AiUpstream;
import com.alibaba.higress.sdk.model.route.UpstreamService;
import com.alibaba.higress.sdk.service.RouteService;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.alibaba.higress.sdk.service.kubernetes.KubernetesClientService;
import com.alibaba.higress.sdk.service.kubernetes.KubernetesModelConverter;
import com.alibaba.higress.sdk.service.kubernetes.crd.istio.V1alpha3EnvoyFilter;
import com.alibaba.higress.sdk.util.MapUtil;
import com.google.gson.Gson;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;

public class AiRouteServiceTest {

    private VelocityEngine velocityEngine;
    private Template routeFallbackEnvoyFilterConfigTemplate;

    @BeforeEach
    public void setUp() {
        this.velocityEngine = new VelocityEngine();
        velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        velocityEngine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
        velocityEngine.init();
        this.routeFallbackEnvoyFilterConfigTemplate =
            velocityEngine.getTemplate("/templates/envoyfilter-route-fallback.yaml", StandardCharsets.UTF_8.name());
    }

    @Test
    public void responseCodeIsNullTest() throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("responseCodes", null);
        StringWriter writer = new StringWriter();
        routeFallbackEnvoyFilterConfigTemplate.merge(context, writer);
        String config = writer.toString();
        Assertions.assertTrue(config.contains("name: \"4xx_response\"") && config.contains("name: \"5xx_response\""));
    }

    @Test
    public void responseCodeIsEmptyTest() throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("responseCodes", Collections.emptyList());
        StringWriter writer = new StringWriter();
        routeFallbackEnvoyFilterConfigTemplate.merge(context, writer);
        String config = writer.toString();
        Assertions.assertTrue(config.contains("name: \"4xx_response\"") && config.contains("name: \"5xx_response\""));
    }

    @Test
    public void responseCodeIs4xxTest() throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("responseCodes", Collections.singletonList("4xx"));
        StringWriter writer = new StringWriter();
        routeFallbackEnvoyFilterConfigTemplate.merge(context, writer);
        String config = writer.toString();
        Assertions.assertTrue(config.contains("name: \"4xx_response\""));
        Assertions.assertFalse(config.contains("name: \"5xx_response\""));
    }

    @Test
    public void responseCodeIs5xxTest() throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("responseCodes", Collections.singletonList("5xx"));
        StringWriter writer = new StringWriter();
        routeFallbackEnvoyFilterConfigTemplate.merge(context, writer);
        String config = writer.toString();
        Assertions.assertFalse(config.contains("name: \"4xx_response\""));
        Assertions.assertTrue(config.contains("name: \"5xx_response\""));
    }

    @Test
    public void responseCodeIs4xxAnd5xxTest() throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("responseCodes", Arrays.asList("4xx", "5xx"));
        StringWriter writer = new StringWriter();
        routeFallbackEnvoyFilterConfigTemplate.merge(context, writer);
        String config = writer.toString();
        Assertions.assertTrue(config.contains("name: \"4xx_response\"") && config.contains("name: \"5xx_response\""));
    }

    /**
     * Regression test: when airoute1 changes its provider from provider1 to provider2,
     * the orphaned ROUTE+SERVICE matchRule for (airoute1, provider1-service) must be deleted.
     *
     * Before the fix, syncRelatedProviders only triggered addOrUpdate for the NEW providers,
     * leaving the old ROUTE+SERVICE matchRule as an orphan.
     */
    @Test
    public void updateShouldCleanUpOrphanedMatchRuleWhenProviderChanges() throws Exception {
        // --- setup mocks ---
        KubernetesClientService k8sClient = mock(KubernetesClientService.class);
        KubernetesModelConverter converter = mock(KubernetesModelConverter.class);
        RouteService routeService = mock(RouteService.class);
        LlmProviderService llmProviderService = mock(LlmProviderService.class);
        WasmPluginInstanceService wasmPluginInstanceService = mock(WasmPluginInstanceService.class);

        // KubernetesClientService.loadFromYaml is called in the constructor to validate the template
        when(k8sClient.loadFromYaml(any(), eq(V1alpha3EnvoyFilter.class)))
            .thenReturn(mock(V1alpha3EnvoyFilter.class));

        AiRouteServiceImpl service = new AiRouteServiceImpl(
            converter, k8sClient, routeService, llmProviderService, wasmPluginInstanceService);

        // --- old route: airoute1 bound to provider1 ---
        AiRoute oldRoute = new AiRoute();
        oldRoute.setName("airoute1");
        AiUpstream oldUpstream = new AiUpstream();
        oldUpstream.setProvider("provider1");
        oldRoute.setUpstreams(Collections.singletonList(oldUpstream));

        // --- new route: airoute1 now bound to provider2 ---
        AiRoute newRoute = new AiRoute();
        newRoute.setName("airoute1");
        AiUpstream newUpstream = new AiUpstream();
        newUpstream.setProvider("provider2");
        newRoute.setUpstreams(Collections.singletonList(newUpstream));

        // query() reads the old ConfigMap to get the old route
        String configMapName = "ai-route-airoute1";
        when(converter.aiRouteName2ConfigMapName("airoute1")).thenReturn(configMapName);

        V1ConfigMap oldConfigMap = buildConfigMap(configMapName, oldRoute);
        when(k8sClient.readConfigMap(configMapName)).thenReturn(oldConfigMap);
        when(converter.configMap2AiRoute(oldConfigMap)).thenReturn(oldRoute);

        // replaceConfigMap returns a new ConfigMap for the updated route
        V1ConfigMap newConfigMap = buildConfigMap(configMapName, newRoute);
        when(converter.aiRoute2ConfigMap(newRoute)).thenReturn(newConfigMap);
        when(k8sClient.replaceConfigMap(newConfigMap)).thenReturn(newConfigMap);
        when(converter.configMap2AiRoute(newConfigMap)).thenReturn(newRoute);

        // provider1's upstream service name (used for orphan cleanup)
        UpstreamService provider1Service = new UpstreamService();
        provider1Service.setName("llm-provider1.dns");
        when(llmProviderService.buildUpstreamService("provider1")).thenReturn(provider1Service);

        // provider2's upstream service name (used in writeAiRouteResources -> setUpstreams)
        UpstreamService provider2Service = new UpstreamService();
        provider2Service.setName("llm-provider2.dns");
        when(llmProviderService.buildUpstreamService("provider2")).thenReturn(provider2Service);

        // syncRelatedProviders queries provider2 — return null so it skips addOrUpdate
        when(llmProviderService.query("provider2")).thenReturn(null);

        // writeAiRouteResources calls routeService.query — return null so it calls add
        when(routeService.query(any())).thenReturn(null);

        // writeAiStatisticsResources calls createEmptyInstance(AI_STATISTICS) when no existing instance
        WasmPluginInstance statsInstance = new WasmPluginInstance();
        when(wasmPluginInstanceService.createEmptyInstance(BuiltInPluginName.AI_STATISTICS))
            .thenReturn(statsInstance);

        // --- execute ---
        service.update(newRoute);

        // --- verify: the orphaned ROUTE+SERVICE matchRule for (airoute1, provider1) must be deleted ---
        Map<WasmPluginInstanceScope, String> expectedTargets = MapUtil.of(
            WasmPluginInstanceScope.ROUTE, "ai-route-airoute1.internal",
            WasmPluginInstanceScope.SERVICE, "llm-provider1.dns");
        verify(wasmPluginInstanceService).delete(eq(expectedTargets), eq(BuiltInPluginName.AI_PROXY), eq(true));
    }

    /**
     * When the provider does NOT change, no orphan cleanup should happen.
     */
    @Test
    public void updateShouldNotDeleteMatchRuleWhenProviderUnchanged() throws Exception {
        KubernetesClientService k8sClient = mock(KubernetesClientService.class);
        KubernetesModelConverter converter = mock(KubernetesModelConverter.class);
        RouteService routeService = mock(RouteService.class);
        LlmProviderService llmProviderService = mock(LlmProviderService.class);
        WasmPluginInstanceService wasmPluginInstanceService = mock(WasmPluginInstanceService.class);

        when(k8sClient.loadFromYaml(any(), eq(V1alpha3EnvoyFilter.class)))
            .thenReturn(mock(V1alpha3EnvoyFilter.class));

        AiRouteServiceImpl service = new AiRouteServiceImpl(
            converter, k8sClient, routeService, llmProviderService, wasmPluginInstanceService);

        AiRoute route = new AiRoute();
        route.setName("airoute1");
        AiUpstream upstream = new AiUpstream();
        upstream.setProvider("provider1");
        route.setUpstreams(Collections.singletonList(upstream));

        String configMapName = "ai-route-airoute1";
        when(converter.aiRouteName2ConfigMapName("airoute1")).thenReturn(configMapName);

        V1ConfigMap configMap = buildConfigMap(configMapName, route);
        when(k8sClient.readConfigMap(configMapName)).thenReturn(configMap);
        when(converter.configMap2AiRoute(configMap)).thenReturn(route);
        when(converter.aiRoute2ConfigMap(route)).thenReturn(configMap);
        when(k8sClient.replaceConfigMap(configMap)).thenReturn(configMap);

        UpstreamService provider1Service = new UpstreamService();
        provider1Service.setName("llm-provider1.dns");
        when(llmProviderService.buildUpstreamService("provider1")).thenReturn(provider1Service);

        when(llmProviderService.query("provider1")).thenReturn(null);
        when(routeService.query(any())).thenReturn(null);

        WasmPluginInstance statsInstance2 = new WasmPluginInstance();
        when(wasmPluginInstanceService.createEmptyInstance(BuiltInPluginName.AI_STATISTICS))
            .thenReturn(statsInstance2);

        service.update(route);

        // provider1 is still in the route — no orphan delete for ai-proxy should be called
        verify(wasmPluginInstanceService, never()).delete(
            eq(MapUtil.of(WasmPluginInstanceScope.ROUTE, "ai-route-airoute1.internal",
                WasmPluginInstanceScope.SERVICE, "llm-provider1.dns")),
            eq(BuiltInPluginName.AI_PROXY), eq(true));
    }

    private static V1ConfigMap buildConfigMap(String name, AiRoute route) {
        V1ConfigMap cm = new V1ConfigMap();
        V1ObjectMeta meta = new V1ObjectMeta();
        meta.setName(name);
        meta.setResourceVersion("1");
        cm.setMetadata(meta);
        Map<String, String> data = new HashMap<>();
        data.put(KubernetesConstants.DATA_FIELD, new Gson().toJson(route));
        cm.setData(data);
        return cm;
    }
}
