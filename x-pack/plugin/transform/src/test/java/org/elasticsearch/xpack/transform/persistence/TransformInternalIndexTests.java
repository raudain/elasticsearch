/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.transform.persistence;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.AdminClient;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexTemplateMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.transform.transforms.persistence.TransformInternalIndexConstants;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class TransformInternalIndexTests extends ESTestCase {

    public static ClusterState STATE_WITH_LATEST_VERSIONED_INDEX;
    public static ClusterState STATE_WITH_LATEST_AUDIT_INDEX_TEMPLATE;

    static {
        ImmutableOpenMap.Builder<String, IndexMetadata> indexMapBuilder = ImmutableOpenMap.builder();
        try {
            IndexMetadata.Builder builder = new IndexMetadata.Builder(TransformInternalIndexConstants.LATEST_INDEX_VERSIONED_NAME)
                .settings(Settings.builder()
                    .put(TransformInternalIndex.settings())
                    .put(IndexMetadata.SETTING_INDEX_VERSION_CREATED.getKey(), Version.CURRENT)
                    .build())
                .numberOfReplicas(0)
                .numberOfShards(1)
                .putMapping(Strings.toString(TransformInternalIndex.mappings()));
            indexMapBuilder.put(TransformInternalIndexConstants.LATEST_INDEX_VERSIONED_NAME, builder.build());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Metadata.Builder metaBuilder = Metadata.builder();
        metaBuilder.indices(indexMapBuilder.build());
        ClusterState.Builder csBuilder = ClusterState.builder(ClusterName.DEFAULT);
        csBuilder.metadata(metaBuilder.build());
        STATE_WITH_LATEST_VERSIONED_INDEX = csBuilder.build();

        ImmutableOpenMap.Builder<String, IndexTemplateMetadata> templateMapBuilder = ImmutableOpenMap.builder();
        try {
            templateMapBuilder.put(TransformInternalIndexConstants.AUDIT_INDEX, TransformInternalIndex.getAuditIndexTemplateMetadata());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        metaBuilder = Metadata.builder();
        metaBuilder.templates(templateMapBuilder.build());
        csBuilder = ClusterState.builder(ClusterName.DEFAULT);
        csBuilder.metadata(metaBuilder.build());
        STATE_WITH_LATEST_AUDIT_INDEX_TEMPLATE = csBuilder.build();
    }

    public void testHaveLatestVersionedIndexTemplate() {

        assertTrue(TransformInternalIndex.haveLatestVersionedIndex(STATE_WITH_LATEST_VERSIONED_INDEX));
        assertFalse(TransformInternalIndex.haveLatestVersionedIndex(ClusterState.EMPTY_STATE));
    }

    public void testCreateLatestVersionedIndexIfRequired_GivenNotRequired() {

        ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.state()).thenReturn(TransformInternalIndexTests.STATE_WITH_LATEST_VERSIONED_INDEX);

        Client client = mock(Client.class);

        AtomicBoolean gotResponse = new AtomicBoolean(false);
        ActionListener<Void> testListener = ActionListener.wrap(aVoid -> gotResponse.set(true), e -> fail(e.getMessage()));

        TransformInternalIndex.createLatestVersionedIndexIfRequired(clusterService, client, testListener);

        assertTrue(gotResponse.get());
        verifyNoMoreInteractions(client);
    }

    public void testCreateLatestVersionedIndexIfRequired_GivenRequired() {

        ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.state()).thenReturn(ClusterState.EMPTY_STATE);

        IndicesAdminClient indicesClient = mock(IndicesAdminClient.class);
        doAnswer(invocationOnMock -> {
            @SuppressWarnings("unchecked")
            ActionListener<CreateIndexResponse> listener = (ActionListener<CreateIndexResponse>) invocationOnMock.getArguments()[1];
            listener.onResponse(new CreateIndexResponse(true, true, TransformInternalIndexConstants.LATEST_INDEX_VERSIONED_NAME));
            return null;
        }).when(indicesClient).create(any(), any());

        AdminClient adminClient = mock(AdminClient.class);
        when(adminClient.indices()).thenReturn(indicesClient);
        Client client = mock(Client.class);
        when(client.admin()).thenReturn(adminClient);

        ThreadPool threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        when(client.threadPool()).thenReturn(threadPool);

        AtomicBoolean gotResponse = new AtomicBoolean(false);
        ActionListener<Void> testListener = ActionListener.wrap(aVoid -> gotResponse.set(true), e -> fail(e.getMessage()));

        TransformInternalIndex.createLatestVersionedIndexIfRequired(clusterService, client, testListener);

        assertTrue(gotResponse.get());
        verify(client, times(1)).threadPool();
        verify(client, times(1)).admin();
        verifyNoMoreInteractions(client);
        verify(adminClient, times(1)).indices();
        verifyNoMoreInteractions(adminClient);
        verify(indicesClient, times(1)).create(any(), any());
        verifyNoMoreInteractions(indicesClient);
    }

    public void testHaveLatestAuditIndexTemplate() {

        assertTrue(TransformInternalIndex.haveLatestAuditIndexTemplate(STATE_WITH_LATEST_AUDIT_INDEX_TEMPLATE));
        assertFalse(TransformInternalIndex.haveLatestAuditIndexTemplate(ClusterState.EMPTY_STATE));
    }

    public void testInstallLatestAuditIndexTemplateIfRequired_GivenNotRequired() {

        ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.state()).thenReturn(TransformInternalIndexTests.STATE_WITH_LATEST_AUDIT_INDEX_TEMPLATE);

        Client client = mock(Client.class);

        AtomicBoolean gotResponse = new AtomicBoolean(false);
        ActionListener<Void> testListener = ActionListener.wrap(aVoid -> gotResponse.set(true), e -> fail(e.getMessage()));

        TransformInternalIndex.installLatestAuditIndexTemplateIfRequired(clusterService, client, testListener);

        assertTrue(gotResponse.get());
        verifyNoMoreInteractions(client);
    }

    public void testInstallLatestAuditIndexTemplateIfRequired_GivenRequired() {

        ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.state()).thenReturn(ClusterState.EMPTY_STATE);

        IndicesAdminClient indicesClient = mock(IndicesAdminClient.class);
        doAnswer(invocationOnMock -> {
            @SuppressWarnings("unchecked")
            ActionListener<AcknowledgedResponse> listener = (ActionListener<AcknowledgedResponse>) invocationOnMock.getArguments()[1];
            listener.onResponse(AcknowledgedResponse.TRUE);
            return null;
        }).when(indicesClient).putTemplate(any(), any());

        AdminClient adminClient = mock(AdminClient.class);
        when(adminClient.indices()).thenReturn(indicesClient);
        Client client = mock(Client.class);
        when(client.admin()).thenReturn(adminClient);

        ThreadPool threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        when(client.threadPool()).thenReturn(threadPool);

        AtomicBoolean gotResponse = new AtomicBoolean(false);
        ActionListener<Void> testListener = ActionListener.wrap(aVoid -> gotResponse.set(true), e -> fail(e.getMessage()));

        TransformInternalIndex.installLatestAuditIndexTemplateIfRequired(clusterService, client, testListener);

        assertTrue(gotResponse.get());
        verify(client, times(1)).threadPool();
        verify(client, times(1)).admin();
        verifyNoMoreInteractions(client);
        verify(adminClient, times(1)).indices();
        verifyNoMoreInteractions(adminClient);
        verify(indicesClient, times(1)).putTemplate(any(), any());
        verifyNoMoreInteractions(indicesClient);
    }

    public void testEnsureLatestIndexAndTemplateInstalled_GivenRequired() {

        ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.state()).thenReturn(ClusterState.EMPTY_STATE);

        IndicesAdminClient indicesClient = mock(IndicesAdminClient.class);
        doAnswer(invocationOnMock -> {
            @SuppressWarnings("unchecked")
            ActionListener<CreateIndexResponse> listener = (ActionListener<CreateIndexResponse>) invocationOnMock.getArguments()[1];
            listener.onResponse(new CreateIndexResponse(true, true, TransformInternalIndexConstants.LATEST_INDEX_VERSIONED_NAME));
            return null;
        }).when(indicesClient).create(any(), any());
        doAnswer(invocationOnMock -> {
            @SuppressWarnings("unchecked")
            ActionListener<AcknowledgedResponse> listener = (ActionListener<AcknowledgedResponse>) invocationOnMock.getArguments()[1];
            listener.onResponse(AcknowledgedResponse.TRUE);
            return null;
        }).when(indicesClient).putTemplate(any(), any());

        AdminClient adminClient = mock(AdminClient.class);
        when(adminClient.indices()).thenReturn(indicesClient);
        Client client = mock(Client.class);
        when(client.admin()).thenReturn(adminClient);

        ThreadPool threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        when(client.threadPool()).thenReturn(threadPool);

        AtomicBoolean gotResponse = new AtomicBoolean(false);
        ActionListener<Void> testListener = ActionListener.wrap(aVoid -> gotResponse.set(true), e -> fail(e.getMessage()));

        TransformInternalIndex.ensureLatestIndexAndTemplateInstalled(clusterService, client, testListener);

        assertTrue(gotResponse.get());
        verify(client, times(2)).threadPool();
        verify(client, times(2)).admin();
        verifyNoMoreInteractions(client);
        verify(adminClient, times(2)).indices();
        verifyNoMoreInteractions(adminClient);
        verify(indicesClient, times(1)).create(any(), any());
        verify(indicesClient, times(1)).putTemplate(any(), any());
        verifyNoMoreInteractions(indicesClient);
    }
}
