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

import static com.alibaba.higress.sdk.constant.plugin.config.AiProxyConfig.PROTOCOL;
import static com.alibaba.higress.sdk.constant.plugin.config.AiProxyConfig.PROVIDERS;
import static com.alibaba.higress.sdk.constant.plugin.config.AiProxyConfig.PROVIDER_ID;
import static com.alibaba.higress.sdk.constant.plugin.config.AiProxyConfig.PROVIDER_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.alibaba.higress.sdk.constant.plugin.BuiltInPluginName;
import com.alibaba.higress.sdk.model.WasmPluginInstance;
import com.alibaba.higress.sdk.model.WasmPluginInstanceScope;
import com.alibaba.higress.sdk.model.ai.LlmProvider;
import com.alibaba.higress.sdk.model.ai.LlmProviderProtocol;
import com.alibaba.higress.sdk.model.ai.LlmProviderType;
import com.alibaba.higress.sdk.service.ServiceSourceService;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.alibaba.higress.sdk.util.MapUtil;

@ExtendWith(MockitoExtension.class)
class LlmProviderServiceTest {

    private static final String PROVIDER_NAME = "tttt";
    private static final String CUSTOM_SERVICE_NAME = "mip-chat-app.static";
    private static final int CUSTOM_SERVICE_PORT = 80;
    private static final String CUSTOM_URL = "http://apisit.midea.com/mip-chat-app/external/openai/standard/v1/chat/completions";

    private ServiceSourceService serviceSourceService;
    private WasmPluginInstanceService wasmPluginInstanceService;
    private LlmProviderServiceImpl service;
    private AtomicReference<WasmPluginInstance> globalInstanceRef;

    @BeforeEach
    void setUp() {
        serviceSourceService = mock(ServiceSourceService.class);
        wasmPluginInstanceService = mock(WasmPluginInstanceService.class);
        service = new LlmProviderServiceImpl(serviceSourceService, wasmPluginInstanceService);
        globalInstanceRef = new AtomicReference<>();

        when(wasmPluginInstanceService.query(eq(WasmPluginInstanceScope.GLOBAL), isNull(),
            eq(BuiltInPluginName.AI_PROXY), eq(true))).thenAnswer(invocation -> globalInstanceRef.get());
        when(wasmPluginInstanceService.createEmptyInstance(eq(BuiltInPluginName.AI_PROXY)))
            .thenAnswer(invocation -> createGlobalInstance(new ArrayList<>()));
        when(wasmPluginInstanceService.addOrUpdate(any())).thenAnswer(invocation -> {
            WasmPluginInstance instance = invocation.getArgument(0);
            if (instance.hasScopedTarget(WasmPluginInstanceScope.GLOBAL)) {
                globalInstanceRef.set(instance);
            }
            return instance;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void addOrUpdateShouldPersistOriginalProtocolForNewProvider() {
        LlmProvider provider = createOpenaiProvider(LlmProviderProtocol.ORIGINAL.getValue());

        LlmProvider result = service.addOrUpdate(provider);

        WasmPluginInstance globalInstance = globalInstanceRef.get();
        assertNotNull(globalInstance);
        List<Map<String, Object>> providers = (List<Map<String, Object>>)globalInstance.getConfigurations().get(PROVIDERS);
        assertEquals(1, providers.size());

        Map<String, Object> providerConfig = providers.get(0);
        assertEquals(PROVIDER_NAME, providerConfig.get(PROVIDER_ID));
        assertEquals(LlmProviderType.OPENAI, providerConfig.get(PROVIDER_TYPE));
        assertEquals(LlmProviderProtocol.ORIGINAL.getPluginValue(), providerConfig.get(PROTOCOL));
        assertEquals(CUSTOM_SERVICE_PORT, providerConfig.get("openaiCustomServicePort"));
        assertEquals(CUSTOM_SERVICE_NAME, providerConfig.get("openaiCustomServiceName"));
        assertEquals(LlmProviderProtocol.ORIGINAL.getValue(), result.getProtocol());
        assertEquals(LlmProviderProtocol.ORIGINAL.getPluginValue(), result.getRawConfigs().get(PROTOCOL));

        verify(serviceSourceService, never()).addOrUpdate(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void addOrUpdateShouldOverwriteProtocolWhenUpdatingProvider() {
        List<Map<String, Object>> existingProviders = new ArrayList<>();
        Map<String, Object> existingProvider = new HashMap<>();
        existingProvider.put(PROVIDER_ID, PROVIDER_NAME);
        existingProvider.put(PROVIDER_TYPE, LlmProviderType.OPENAI);
        existingProvider.put(PROTOCOL, LlmProviderProtocol.OPENAI_V1.getPluginValue());
        existingProvider.put("openaiCustomServiceName", CUSTOM_SERVICE_NAME);
        existingProvider.put("openaiCustomServicePort", CUSTOM_SERVICE_PORT);
        existingProvider.put("openaiCustomUrl", CUSTOM_URL);
        existingProviders.add(existingProvider);
        globalInstanceRef.set(createGlobalInstance(existingProviders));

        LlmProvider provider = createOpenaiProvider(LlmProviderProtocol.ORIGINAL.getValue());

        LlmProvider result = service.addOrUpdate(provider);

        WasmPluginInstance globalInstance = globalInstanceRef.get();
        assertNotNull(globalInstance);
        List<Map<String, Object>> providers = (List<Map<String, Object>>)globalInstance.getConfigurations().get(PROVIDERS);
        assertEquals(1, providers.size());

        Map<String, Object> providerConfig = providers.get(0);
        assertEquals(LlmProviderProtocol.ORIGINAL.getPluginValue(), providerConfig.get(PROTOCOL));
        assertEquals(CUSTOM_SERVICE_PORT, providerConfig.get("openaiCustomServicePort"));
        assertEquals(CUSTOM_SERVICE_NAME, providerConfig.get("openaiCustomServiceName"));
        assertTrue(providerConfig.containsKey(PROTOCOL));
        assertEquals(LlmProviderProtocol.ORIGINAL.getValue(), result.getProtocol());
        assertEquals(LlmProviderProtocol.ORIGINAL.getPluginValue(), result.getRawConfigs().get(PROTOCOL));
    }

    private static LlmProvider createOpenaiProvider(String protocol) {
        Map<String, Object> rawConfigs = new HashMap<>();
        rawConfigs.put("openaiCustomUrl", CUSTOM_URL);
        rawConfigs.put("openaiCustomServiceName", CUSTOM_SERVICE_NAME);
        rawConfigs.put("openaiCustomServicePort", CUSTOM_SERVICE_PORT);

        LlmProvider provider = new LlmProvider();
        provider.setName(PROVIDER_NAME);
        provider.setType(LlmProviderType.OPENAI);
        provider.setProtocol(protocol);
        provider.setRawConfigs(rawConfigs);
        provider.setTokens(new ArrayList<>());
        return provider;
    }

    private static WasmPluginInstance createGlobalInstance(List<Map<String, Object>> providers) {
        WasmPluginInstance instance = new WasmPluginInstance();
        instance.setPluginName(BuiltInPluginName.AI_PROXY);
        instance.setPluginVersion("1.0.0");
        instance.setInternal(true);
        instance.setEnabled(true);
        instance.setGlobalTarget();
        instance.setConfigurations(new HashMap<>(MapUtil.of(PROVIDERS, providers)));
        return instance;
    }
}
