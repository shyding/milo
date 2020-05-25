/*
 * Copyright (c) 2019 the Eclipse Milo Authors
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.milo.opcua.sdk.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Sets;
import org.eclipse.milo.opcua.sdk.client.ObjectTypeManager.ObjectNodeConstructor;
import org.eclipse.milo.opcua.sdk.client.model.nodes.objects.ServerTypeNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaDataTypeNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaMethodNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaObjectNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaObjectTypeNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaReferenceTypeNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableTypeNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaViewNode;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.BuiltinReferenceType;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExpandedNodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadResponse;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.eclipse.milo.opcua.stack.core.util.FutureUtils;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort;
import static org.eclipse.milo.opcua.stack.core.util.ConversionUtil.l;

public class AddressSpace {

    private volatile Duration expireAfter = Duration.ofMinutes(2);
    private volatile long maximumSize = 1024;
    private final Cache<NodeId, UaNode> cache = buildCache();

    private BrowseOptions browseOptions = new BrowseOptions();

    private final OpcUaClient client;

    public AddressSpace(OpcUaClient client) {
        this.client = client;
    }

    /**
     * Get a {@link UaNode} instance for the Node identified by {@code nodeId}.
     *
     * @param nodeId the {@link NodeId} identifying the Node to get.
     * @return a {@link UaNode} instance for the Node identified by {@code nodeId}.
     * @throws UaException if an error occurs while creating the Node.
     */
    public UaNode getNode(NodeId nodeId) throws UaException {
        try {
            return getNodeAsync(nodeId).get();
        } catch (ExecutionException | InterruptedException e) {
            throw UaException.extract(e)
                .orElse(new UaException(StatusCodes.Bad_UnexpectedError, e));
        }
    }

    public CompletableFuture<? extends UaNode> getNodeAsync(NodeId nodeId) {
        UaNode cachedNode = cache.getIfPresent(nodeId);

        if (cachedNode != null) {
            return completedFuture(cachedNode);
        } else {
            return createNode(nodeId).whenComplete((node, ex) -> {
                if (node != null) {
                    cache.put(nodeId, node);
                }
            });
        }
    }

    /**
     * Get a {@link UaObjectNode} instance for the ObjectNode identified by {@code nodeId}.
     * <p>
     * The type definition will be read when the instance is created. If this type definition is
     * registered with the {@link ObjectTypeManager} a {@link UaObjectNode} of the appropriate
     * subclass will be returned.
     *
     * @param nodeId the {@link NodeId} identifying the ObjectNode to get.
     * @return a {@link UaObjectNode} instance for the ObjectNode identified by {@code nodeId}.
     * @throws UaException if an error occurs while creating the ObjectNode.
     */
    public UaObjectNode getObjectNode(NodeId nodeId) throws UaException {
        try {
            return getObjectNodeAsync(nodeId).get();
        } catch (ExecutionException | InterruptedException e) {
            throw UaException.extract(e)
                .orElse(new UaException(StatusCodes.Bad_UnexpectedError, e));
        }
    }

    /**
     * Get a {@link UaObjectNode} instance for the ObjectNode identified by {@code nodeId},
     * assuming the type definition identified by {@code typeDefinitionId}.
     * <p>
     * If this type definition is registered with the {@link ObjectTypeManager} a
     * {@link UaObjectNode} of the appropriate subclass will be returned.
     *
     * @param nodeId           the {@link NodeId} identifying the ObjectNode to get.
     * @param typeDefinitionId the {@link NodeId} identifying the type definition.
     * @return a {@link UaObjectNode} instance for the ObjectNode identified by {@code nodeId}.
     * @throws UaException if an error occurs while creating the ObjectNode.
     */
    public UaObjectNode getObjectNode(NodeId nodeId, NodeId typeDefinitionId) throws UaException {
        try {
            return getObjectNodeAsync(nodeId, typeDefinitionId).get();
        } catch (ExecutionException | InterruptedException e) {
            throw UaException.extract(e)
                .orElse(new UaException(StatusCodes.Bad_UnexpectedError, e));
        }
    }

    public CompletableFuture<UaObjectNode> getObjectNodeAsync(NodeId nodeId) {
        UaNode cachedNode = cache.getIfPresent(nodeId);

        if (cachedNode instanceof UaObjectNode) {
            return completedFuture((UaObjectNode) cachedNode);
        } else {
            CompletableFuture<NodeId> typeDefinitionFuture = readTypeDefinition(nodeId);

            return typeDefinitionFuture.thenCompose(typeDefinitionId -> getObjectNodeAsync(nodeId, typeDefinitionId));
        }
    }

    public CompletableFuture<UaObjectNode> getObjectNodeAsync(NodeId nodeId, NodeId typeDefinitionId) {
        UaNode cachedNode = cache.getIfPresent(nodeId);

        if (cachedNode instanceof UaObjectNode) {
            return completedFuture((UaObjectNode) cachedNode);
        } else {
            List<ReadValueId> readValueIds = AttributeId.OBJECT_ATTRIBUTES.stream()
                .map(id ->
                    new ReadValueId(
                        nodeId,
                        id.uid(),
                        null,
                        QualifiedName.NULL_VALUE
                    )
                )
                .collect(Collectors.toList());

            CompletableFuture<ReadResponse> future = client.read(
                0.0,
                TimestampsToReturn.Neither,
                readValueIds
            );

            return future.thenApply(response -> {
                List<DataValue> attributeValues = l(response.getResults());

                UaObjectNode node = newObjectNode(nodeId, typeDefinitionId, attributeValues);

                cache.put(node.getNodeId(), node);

                return node;
            });
        }
    }

    /**
     * Get a {@link UaVariableNode} instance for the VariableNode identified by {@code nodeId}.
     * <p>
     * The type definition will be read when the instance is created. If this type definition is
     * registered with the {@link VariableTypeManager} a {@link UaVariableNode} of the appropriate
     * subclass will be returned.
     *
     * @param nodeId the {@link NodeId} identifying the VariableNode to get.
     * @return a {@link UaVariableNode} instance for the VariableNode identified by {@code nodeId}.
     * @throws UaException if an error occurs while creating the VariableNode.
     */
    public UaVariableNode getVariableNode(NodeId nodeId) throws UaException {
        try {
            return getVariableNodeAsync(nodeId).get();
        } catch (ExecutionException | InterruptedException e) {
            throw UaException.extract(e)
                .orElse(new UaException(StatusCodes.Bad_UnexpectedError, e));
        }
    }

    /**
     * Get a {@link UaVariableNode} instance for the VariableNode identified by {@code nodeId},
     * assuming the type definition identified by {@code typeDefinitionId}.
     * <p>
     * If this type definition is registered with the {@link VariableTypeManager} a
     * {@link UaVariableNode} of the appropriate subclass will be returned.
     *
     * @param nodeId           the {@link NodeId} identifying the VariableNode to get.
     * @param typeDefinitionId the {@link NodeId} identifying the type definition.
     * @return a {@link UaVariableNode} instance for the VariableNode identified by {@code nodeId}.
     * @throws UaException if an error occurs while creating the VariableNode.
     */
    public UaVariableNode getVariableNode(NodeId nodeId, NodeId typeDefinitionId) throws UaException {
        try {
            return getVariableNodeAsync(nodeId, typeDefinitionId).get();
        } catch (ExecutionException | InterruptedException e) {
            throw UaException.extract(e)
                .orElse(new UaException(StatusCodes.Bad_UnexpectedError, e));
        }
    }

    public CompletableFuture<UaVariableNode> getVariableNodeAsync(NodeId nodeId) {
        UaNode cachedNode = cache.getIfPresent(nodeId);

        if (cachedNode instanceof UaVariableNode) {
            return completedFuture((UaVariableNode) cachedNode);
        } else {
            CompletableFuture<NodeId> typeDefinitionFuture = readTypeDefinition(nodeId);

            return typeDefinitionFuture.thenCompose(typeDefinitionId -> getVariableNodeAsync(nodeId, typeDefinitionId));
        }
    }

    public CompletableFuture<UaVariableNode> getVariableNodeAsync(NodeId nodeId, NodeId typeDefinitionId) {
        UaNode cachedNode = cache.getIfPresent(nodeId);

        if (cachedNode instanceof UaVariableNode) {
            return completedFuture((UaVariableNode) cachedNode);
        } else {
            List<ReadValueId> readValueIds = AttributeId.VARIABLE_ATTRIBUTES.stream()
                .map(id ->
                    new ReadValueId(
                        nodeId,
                        id.uid(),
                        null,
                        QualifiedName.NULL_VALUE
                    )
                )
                .collect(Collectors.toList());

            CompletableFuture<ReadResponse> future = client.read(
                0.0,
                TimestampsToReturn.Neither,
                readValueIds
            );

            return future.thenApply(response -> {
                List<DataValue> attributeValues = l(response.getResults());

                UaVariableNode node = newVariableNode(nodeId, typeDefinitionId, attributeValues);

                cache.put(node.getNodeId(), node);

                return node;
            });
        }
    }

    /**
     * Browse from {@code node} using the currently configured {@link BrowseOptions}.
     *
     * @param node the {@link UaNode} to start the browse from.
     * @return a List of {@link UaNode}s referenced by {@code node} given the currently configured
     * {@link BrowseOptions}.
     * @throws UaException if an error occurs while browsing or creating Nodes.
     * @see #browseNode(UaNode, BrowseOptions)
     * @see #getBrowseOptions()
     * @see #modifyBrowseOptions(Consumer)
     * @see #setBrowseOptions(BrowseOptions)
     */
    public List<? extends UaNode> browseNode(UaNode node) throws UaException {
        return browseNode(node, getBrowseOptions());
    }

    /**
     * Browse from {@code node} using {@code browseOptions}.
     *
     * @param node          the {@link UaNode} to start the browse from.
     * @param browseOptions the {@link BrowseOptions} to use.
     * @return a List of {@link UaNode}s referenced by {@code node} given {@code browseOptions}.
     * @throws UaException if an error occurs while browsing or creating Nodes.
     */
    public List<? extends UaNode> browseNode(UaNode node, BrowseOptions browseOptions) throws UaException {
        try {
            return browseNodeAsync(node, browseOptions).get();
        } catch (ExecutionException | InterruptedException e) {
            throw UaException.extract(e)
                .orElse(new UaException(StatusCodes.Bad_UnexpectedError, e));
        }
    }

    /**
     * Browse from {@code nodeId} using the currently configured {@link BrowseOptions}.
     *
     * @param nodeId the {@link NodeId} to start the browse from.
     * @return a List of {@link UaNode}s referenced by {@code nodeId} given the currently configured
     * {@link BrowseOptions}.
     * @throws UaException if an error occurs while browsing or creating Nodes.
     * @see #browseNode(UaNode, BrowseOptions)
     * @see #getBrowseOptions()
     * @see #modifyBrowseOptions(Consumer)
     * @see #setBrowseOptions(BrowseOptions)
     */
    public List<? extends UaNode> browseNode(NodeId nodeId) throws UaException {
        return browseNode(nodeId, getBrowseOptions());
    }

    /**
     * Browse from {@code nodeId} using {@code browseOptions}.
     *
     * @param nodeId        the {@link NodeId} to start the browse from.
     * @param browseOptions the {@link BrowseOptions} to use.
     * @return a List of {@link UaNode}s referenced by {@code nodeId} given {@code browseOptions}.
     * @throws UaException if an error occurs while browsing or creating Nodes.
     */
    public List<? extends UaNode> browseNode(NodeId nodeId, BrowseOptions browseOptions) throws UaException {
        try {
            return browseNodeAsync(nodeId, browseOptions).get();
        } catch (ExecutionException | InterruptedException e) {
            throw UaException.extract(e)
                .orElse(new UaException(StatusCodes.Bad_UnexpectedError, e));
        }
    }

    public CompletableFuture<List<? extends UaNode>> browseNodeAsync(UaNode node) {
        return browseNodeAsync(node.getNodeId());
    }

    public CompletableFuture<List<? extends UaNode>> browseNodeAsync(UaNode node, BrowseOptions browseOptions) {
        return browseNodeAsync(node.getNodeId(), browseOptions);
    }

    public CompletableFuture<List<? extends UaNode>> browseNodeAsync(NodeId nodeId) {
        return browseNodeAsync(nodeId, getBrowseOptions());
    }

    public CompletableFuture<List<? extends UaNode>> browseNodeAsync(NodeId nodeId, BrowseOptions browseOptions) {
        BrowseDescription browseDescription = new BrowseDescription(
            nodeId,
            browseOptions.getBrowseDirection(),
            browseOptions.getReferenceTypeId(),
            browseOptions.isIncludeSubtypes(),
            browseOptions.getNodeClassMask(),
            uint(BrowseResultMask.All.getValue())
        );

        CompletableFuture<List<ReferenceDescription>> browse = BrowseHelper.browse(client, browseDescription);

        return browse.thenCompose(references -> {
            List<CompletableFuture<? extends UaNode>> cfs = references.stream()
                .map(reference -> {
                    NodeClass nodeClass = reference.getNodeClass();
                    ExpandedNodeId xNodeId = reference.getNodeId();
                    ExpandedNodeId xTypeDefinitionId = reference.getTypeDefinition();

                    switch (nodeClass) {
                        case Object:
                        case Variable: {
                            CompletableFuture<CompletableFuture<? extends UaNode>> ff =
                                localizeAsync(xNodeId).thenCombine(
                                    localizeAsync(xTypeDefinitionId),
                                    (targetNodeId, typeDefinitionId) -> {
                                        if (nodeClass == NodeClass.Object) {
                                            return getObjectNodeAsync(targetNodeId, typeDefinitionId);
                                        } else {
                                            return getVariableNodeAsync(targetNodeId, typeDefinitionId);
                                        }
                                    }
                                );

                            return unwrap(ff);
                        }
                        default: {
                            // TODO specialized getNode for other NodeClasses?
                            return localizeAsync(xNodeId).thenCompose(this::getNodeAsync);
                        }
                    }
                })
                .collect(Collectors.toList());

            return sequence(cfs);
        });
    }

    private static CompletableFuture<List<? extends UaNode>> sequence(
        List<CompletableFuture<? extends UaNode>> cfs
    ) {

        if (cfs.isEmpty()) {
            return completedFuture(Collections.emptyList());
        }

        @SuppressWarnings("rawtypes")
        CompletableFuture[] fa = cfs.toArray(new CompletableFuture[0]);

        return CompletableFuture.allOf(fa).thenApply(v -> {
            List<UaNode> results = new ArrayList<>(cfs.size());

            for (CompletableFuture<? extends UaNode> cf : cfs) {
                results.add(cf.join());
            }

            return results;
        });
    }

    private static CompletableFuture<? extends UaNode> unwrap(
        CompletableFuture<CompletableFuture<? extends UaNode>> future
    ) {

        return future.thenCompose(node -> node);
    }

    public NodeId localize(ExpandedNodeId nodeId) throws UaException {
        try {
            return localizeAsync(nodeId).get();
        } catch (ExecutionException | InterruptedException e) {
            throw UaException.extract(e)
                .orElse(new UaException(StatusCodes.Bad_UnexpectedError, e));
        }
    }

    public CompletableFuture<NodeId> localizeAsync(ExpandedNodeId nodeId) {
        // TODO should this fail with Bad_NodeIdUnknown instead of returning NodeId.NULL_VALUE?
        if (nodeId.isLocal()) {
            Optional<NodeId> local = nodeId.local(client.getNamespaceTable());

            if (local.isPresent()) {
                return completedFuture(local.orElse(NodeId.NULL_VALUE));
            } else {
                return getObjectNodeAsync(Identifiers.Server).thenCompose(node -> {
                    ServerTypeNode serverNode = (ServerTypeNode) node;
                    return serverNode.readNamespaceArrayAsync();
                }).thenCompose((String[] namespaceArray) -> {
                    client.getNamespaceTable().update(uriTable -> {
                        uriTable.clear();

                        for (int i = 0; i < namespaceArray.length && i < UShort.MAX_VALUE; i++) {
                            String uri = namespaceArray[i];

                            if (uri != null && !uriTable.containsValue(uri)) {
                                uriTable.put(ushort(i), uri);
                            }
                        }
                    });

                    return completedFuture(local.orElse(NodeId.NULL_VALUE));
                });
            }
        } else {
            return completedFuture(NodeId.NULL_VALUE);
        }
    }

    public synchronized BrowseOptions getBrowseOptions() {
        return browseOptions;
    }

    public synchronized void modifyBrowseOptions(Consumer<BrowseOptions.Builder> builderConsumer) {
        BrowseOptions.Builder builder = new BrowseOptions.Builder();
        builder.setReferenceType(browseOptions.getReferenceTypeId());
        builder.setIncludeSubtypes(browseOptions.isIncludeSubtypes());
        builder.setNodeClassMask(browseOptions.getNodeClassMask());

        builderConsumer.accept(builder);

        setBrowseOptions(builder.build());
    }

    public synchronized void setBrowseOptions(BrowseOptions browseOptions) {
        this.browseOptions = browseOptions;
    }

    private CompletableFuture<NodeId> readTypeDefinition(NodeId nodeId) {
        CompletableFuture<BrowseResult> browseFuture = client.browse(new BrowseDescription(
            nodeId,
            BrowseDirection.Forward,
            Identifiers.HasTypeDefinition,
            false,
            uint(NodeClass.ObjectType.getValue() | NodeClass.VariableType.getValue()),
            uint(BrowseResultMask.All.getValue())
        ));

        return browseFuture.thenApply(result -> {
            if (result.getStatusCode().isGood()) {
                Optional<ExpandedNodeId> typeDefinitionId = Arrays.stream(result.getReferences())
                    .filter(r -> Objects.equals(Identifiers.HasTypeDefinition, r.getReferenceTypeId()))
                    .map(ReferenceDescription::getNodeId)
                    .findFirst();

                // TODO better xni -> local function that looks in the current
                //  namespace table and reads from server as a fallback.
                return typeDefinitionId
                    .flatMap(xni -> xni.local(client.getNamespaceTable()))
                    .orElse(NodeId.NULL_VALUE);
            } else {
                return NodeId.NULL_VALUE;
            }
        });
    }

    /**
     * Create a {@link UaNode} instance without prior knowledge of the {@link NodeClass} or type
     * definition, if applicable.
     *
     * @param nodeId the {@link NodeId} of the Node to create.
     * @return a {@link UaNode} instance for the Node identified by {@code nodeId}.
     */
    private CompletableFuture<? extends UaNode> createNode(NodeId nodeId) {
        List<ReadValueId> readValueIds = AttributeId.BASE_ATTRIBUTES.stream()
            .map(id ->
                new ReadValueId(
                    nodeId,
                    id.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )
            )
            .collect(Collectors.toList());

        CompletableFuture<ReadResponse> future = client.read(
            0.0,
            TimestampsToReturn.Neither,
            readValueIds
        );

        return future.thenCompose(response -> {
            List<DataValue> results = l(response.getResults());

            return createNodeFromBaseAttributes(nodeId, results);
        });
    }

    private CompletableFuture<? extends UaNode> createNodeFromBaseAttributes(
        NodeId nodeId,
        List<DataValue> baseAttributeValues
    ) {

        Integer nodeClassValue = (Integer) baseAttributeValues.get(1).getValue().getValue();
        if (nodeClassValue == null) {
            return FutureUtils.failedUaFuture(StatusCodes.Bad_NodeClassInvalid);
        }
        NodeClass nodeClass = NodeClass.from(nodeClassValue);
        if (nodeClass == null) {
            return FutureUtils.failedUaFuture(StatusCodes.Bad_NodeClassInvalid);
        }

        switch (nodeClass) {
            case DataType:
                return createDataTypeNodeFromBaseAttributes(nodeId, baseAttributeValues);
            case Method:
                return createMethodNodeFromBaseAttributes(nodeId, baseAttributeValues);
            case Object:
                return createObjectNodeFromBaseAttributes(nodeId, baseAttributeValues);
            case ObjectType:
                return createObjectTypeNodeFromBaseAttributes(nodeId, baseAttributeValues);
            case ReferenceType:
                return createReferenceTypeNodeFromBaseAttributes(nodeId, baseAttributeValues);
            case Variable:
                return createVariableNodeFromBaseAttributes(nodeId, baseAttributeValues);
            case VariableType:
                return createVariableTypeNodeFromBaseAttributes(nodeId, baseAttributeValues);
            case View:
                return createViewNodeFromBaseAttributes(nodeId, baseAttributeValues);
            default:
                throw new IllegalArgumentException("NodeClass: " + nodeClass);
        }
    }

    private CompletableFuture<UaDataTypeNode> createDataTypeNodeFromBaseAttributes(
        NodeId nodeId,
        List<DataValue> baseAttributeValues
    ) {

        Set<AttributeId> remainingAttributes = Sets.difference(
            AttributeId.DATA_TYPE_ATTRIBUTES,
            AttributeId.BASE_ATTRIBUTES
        );

        List<ReadValueId> readValueIds = remainingAttributes.stream()
            .map(id ->
                new ReadValueId(
                    nodeId,
                    id.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )
            )
            .collect(Collectors.toList());

        CompletableFuture<ReadResponse> attributesFuture = client.read(
            0.0,
            TimestampsToReturn.Neither,
            readValueIds
        );

        return attributesFuture.thenApply(response -> {
            List<DataValue> attributeValues = new ArrayList<>(baseAttributeValues);
            Collections.addAll(attributeValues, response.getResults());

            UaDataTypeNode node = newDataTypeNode(nodeId, attributeValues);

            cache.put(node.getNodeId(), node);

            return node;
        });
    }

    private CompletableFuture<UaMethodNode> createMethodNodeFromBaseAttributes(
        NodeId nodeId,
        List<DataValue> baseAttributeValues
    ) {

        Set<AttributeId> remainingAttributes = Sets.difference(
            AttributeId.METHOD_ATTRIBUTES,
            AttributeId.BASE_ATTRIBUTES
        );

        List<ReadValueId> readValueIds = remainingAttributes.stream()
            .map(id ->
                new ReadValueId(
                    nodeId,
                    id.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )
            )
            .collect(Collectors.toList());

        CompletableFuture<ReadResponse> attributesFuture = client.read(
            0.0,
            TimestampsToReturn.Neither,
            readValueIds
        );

        return attributesFuture.thenApply(response -> {
            List<DataValue> attributeValues = new ArrayList<>(baseAttributeValues);
            Collections.addAll(attributeValues, response.getResults());

            UaMethodNode node = newMethodNode(nodeId, attributeValues);

            cache.put(node.getNodeId(), node);

            return node;
        });
    }

    private CompletableFuture<UaObjectNode> createObjectNodeFromBaseAttributes(
        NodeId nodeId,
        List<DataValue> baseAttributeValues
    ) {

        Set<AttributeId> remainingAttributes = Sets.difference(
            AttributeId.OBJECT_ATTRIBUTES,
            AttributeId.BASE_ATTRIBUTES
        );

        List<ReadValueId> readValueIds = remainingAttributes.stream()
            .map(id ->
                new ReadValueId(
                    nodeId,
                    id.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )
            )
            .collect(Collectors.toList());

        CompletableFuture<ReadResponse> attributesFuture = client.read(
            0.0,
            TimestampsToReturn.Neither,
            readValueIds
        );

        CompletableFuture<NodeId> typeDefinitionFuture = readTypeDefinition(nodeId);

        return attributesFuture.thenCombine(typeDefinitionFuture, (response, typeDefinitionId) -> {
            List<DataValue> attributeValues = new ArrayList<>(baseAttributeValues);
            Collections.addAll(attributeValues, response.getResults());

            UaObjectNode node = newObjectNode(nodeId, typeDefinitionId, attributeValues);

            cache.put(node.getNodeId(), node);

            return node;
        });
    }

    private CompletableFuture<UaObjectTypeNode> createObjectTypeNodeFromBaseAttributes(
        NodeId nodeId,
        List<DataValue> baseAttributeValues
    ) {

        Set<AttributeId> remainingAttributes = Sets.difference(
            AttributeId.OBJECT_TYPE_ATTRIBUTES,
            AttributeId.BASE_ATTRIBUTES
        );

        List<ReadValueId> readValueIds = remainingAttributes.stream()
            .map(id ->
                new ReadValueId(
                    nodeId,
                    id.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )
            )
            .collect(Collectors.toList());

        CompletableFuture<ReadResponse> attributesFuture = client.read(
            0.0,
            TimestampsToReturn.Neither,
            readValueIds
        );

        return attributesFuture.thenApply(response -> {
            List<DataValue> attributeValues = new ArrayList<>(baseAttributeValues);
            Collections.addAll(attributeValues, response.getResults());

            UaObjectTypeNode node = newObjectTypeNode(nodeId, attributeValues);

            cache.put(node.getNodeId(), node);

            return node;
        });
    }

    private CompletableFuture<UaReferenceTypeNode> createReferenceTypeNodeFromBaseAttributes(
        NodeId nodeId,
        List<DataValue> baseAttributeValues
    ) {

        Set<AttributeId> remainingAttributes = Sets.difference(
            AttributeId.REFERENCE_TYPE_ATTRIBUTES,
            AttributeId.BASE_ATTRIBUTES
        );

        List<ReadValueId> readValueIds = remainingAttributes.stream()
            .map(id ->
                new ReadValueId(
                    nodeId,
                    id.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )
            )
            .collect(Collectors.toList());

        CompletableFuture<ReadResponse> attributesFuture = client.read(
            0.0,
            TimestampsToReturn.Neither,
            readValueIds
        );

        return attributesFuture.thenApply(response -> {
            List<DataValue> attributeValues = new ArrayList<>(baseAttributeValues);
            Collections.addAll(attributeValues, response.getResults());

            UaReferenceTypeNode node = newReferenceTypeNode(nodeId, attributeValues);

            cache.put(node.getNodeId(), node);

            return node;
        });
    }

    private CompletableFuture<UaVariableNode> createVariableNodeFromBaseAttributes(
        NodeId nodeId,
        List<DataValue> baseAttributeValues
    ) {

        Set<AttributeId> remainingAttributes = Sets.difference(
            AttributeId.VARIABLE_ATTRIBUTES,
            AttributeId.BASE_ATTRIBUTES
        );

        List<ReadValueId> readValueIds = remainingAttributes.stream()
            .map(id ->
                new ReadValueId(
                    nodeId,
                    id.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )
            )
            .collect(Collectors.toList());

        CompletableFuture<ReadResponse> attributesFuture = client.read(
            0.0,
            TimestampsToReturn.Neither,
            readValueIds
        );

        CompletableFuture<NodeId> typeDefinitionFuture = readTypeDefinition(nodeId);

        return attributesFuture.thenCombine(typeDefinitionFuture, (response, typeDefinitionId) -> {
            List<DataValue> attributeValues = new ArrayList<>(baseAttributeValues);
            Collections.addAll(attributeValues, response.getResults());

            UaVariableNode node = newVariableNode(nodeId, typeDefinitionId, attributeValues);

            cache.put(node.getNodeId(), node);

            return node;
        });
    }

    private CompletableFuture<UaVariableTypeNode> createVariableTypeNodeFromBaseAttributes(
        NodeId nodeId,
        List<DataValue> baseAttributeValues
    ) {

        Set<AttributeId> remainingAttributes = Sets.difference(
            AttributeId.VARIABLE_TYPE_ATTRIBUTES,
            AttributeId.BASE_ATTRIBUTES
        );

        List<ReadValueId> readValueIds = remainingAttributes.stream()
            .map(id ->
                new ReadValueId(
                    nodeId,
                    id.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )
            )
            .collect(Collectors.toList());

        CompletableFuture<ReadResponse> attributesFuture = client.read(
            0.0,
            TimestampsToReturn.Neither,
            readValueIds
        );

        return attributesFuture.thenApply(response -> {
            List<DataValue> attributeValues = new ArrayList<>(baseAttributeValues);
            Collections.addAll(attributeValues, response.getResults());

            UaVariableTypeNode node = newVariableTypeNode(nodeId, attributeValues);

            cache.put(node.getNodeId(), node);

            return node;
        });
    }

    private CompletableFuture<UaViewNode> createViewNodeFromBaseAttributes(
        NodeId nodeId,
        List<DataValue> baseAttributeValues
    ) {

        Set<AttributeId> remainingAttributes = Sets.difference(
            AttributeId.VIEW_ATTRIBUTES,
            AttributeId.BASE_ATTRIBUTES
        );

        List<ReadValueId> readValueIds = remainingAttributes.stream()
            .map(id ->
                new ReadValueId(
                    nodeId,
                    id.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )
            )
            .collect(Collectors.toList());

        CompletableFuture<ReadResponse> attributesFuture = client.read(
            0.0,
            TimestampsToReturn.Neither,
            readValueIds
        );

        return attributesFuture.thenApply(response -> {
            List<DataValue> attributeValues = new ArrayList<>(baseAttributeValues);
            Collections.addAll(attributeValues, response.getResults());

            UaViewNode node = newViewNode(nodeId, attributeValues);

            cache.put(node.getNodeId(), node);

            return node;
        });
    }

    private UaDataTypeNode newDataTypeNode(NodeId nodeId, List<DataValue> attributeValues) {
        NodeClass nodeClass = NodeClass.from((Integer) attributeValues.get(1).getValue().getValue());

        Preconditions.checkArgument(
            nodeClass == NodeClass.DataType,
            "expected NodeClass.DataType, got NodeClass." + nodeClass
        );

        QualifiedName browseName = (QualifiedName) attributeValues.get(2).getValue().getValue();
        LocalizedText displayName = (LocalizedText) attributeValues.get(3).getValue().getValue();
        LocalizedText description = (LocalizedText) attributeValues.get(4).getValue().getValue();
        UInteger writeMask = (UInteger) attributeValues.get(5).getValue().getValue();
        UInteger userWriteMask = (UInteger) attributeValues.get(6).getValue().getValue();

        Boolean isAbstract = (Boolean) attributeValues.get(7).getValue().getValue();

        return new UaDataTypeNode(
            client,
            nodeId,
            nodeClass,
            browseName,
            displayName,
            description,
            writeMask,
            userWriteMask,
            isAbstract
        );
    }

    private UaMethodNode newMethodNode(NodeId nodeId, List<DataValue> attributeValues) {
        NodeClass nodeClass = NodeClass.from((Integer) attributeValues.get(1).getValue().getValue());

        Preconditions.checkArgument(
            nodeClass == NodeClass.Method,
            "expected NodeClass.Method, got NodeClass." + nodeClass
        );

        QualifiedName browseName = (QualifiedName) attributeValues.get(2).getValue().getValue();
        LocalizedText displayName = (LocalizedText) attributeValues.get(3).getValue().getValue();
        LocalizedText description = (LocalizedText) attributeValues.get(4).getValue().getValue();
        UInteger writeMask = (UInteger) attributeValues.get(5).getValue().getValue();
        UInteger userWriteMask = (UInteger) attributeValues.get(6).getValue().getValue();

        Boolean executable = (Boolean) attributeValues.get(7).getValue().getValue();
        Boolean userExecutable = (Boolean) attributeValues.get(8).getValue().getValue();

        return new UaMethodNode(
            client,
            nodeId,
            nodeClass,
            browseName,
            displayName,
            description,
            writeMask,
            userWriteMask,
            executable,
            userExecutable
        );
    }

    private UaObjectNode newObjectNode(NodeId nodeId, NodeId typeDefinitionId, List<DataValue> attributeValues) {
        NodeClass nodeClass = NodeClass.from((Integer) attributeValues.get(1).getValue().getValue());

        Preconditions.checkArgument(
            nodeClass == NodeClass.Object,
            "expected NodeClass.Object, got NodeClass." + nodeClass
        );

        QualifiedName browseName = (QualifiedName) attributeValues.get(2).getValue().getValue();
        LocalizedText displayName = (LocalizedText) attributeValues.get(3).getValue().getValue();
        LocalizedText description = (LocalizedText) attributeValues.get(4).getValue().getValue();
        UInteger writeMask = (UInteger) attributeValues.get(5).getValue().getValue();
        UInteger userWriteMask = (UInteger) attributeValues.get(6).getValue().getValue();

        UByte eventNotifier = (UByte) attributeValues.get(7).getValue().getValue();

        ObjectNodeConstructor constructor = client.getObjectTypeManager()
            .getNodeConstructor(typeDefinitionId)
            .orElse(UaObjectNode::new);

        return constructor.apply(
            client,
            nodeId,
            nodeClass,
            browseName,
            displayName,
            description,
            writeMask,
            userWriteMask,
            eventNotifier
        );
    }

    private UaObjectTypeNode newObjectTypeNode(NodeId nodeId, List<DataValue> attributeValues) {
        NodeClass nodeClass = NodeClass.from((Integer) attributeValues.get(1).getValue().getValue());

        Preconditions.checkArgument(
            nodeClass == NodeClass.ObjectType,
            "expected NodeClass.ObjectType, got NodeClass." + nodeClass
        );

        QualifiedName browseName = (QualifiedName) attributeValues.get(2).getValue().getValue();
        LocalizedText displayName = (LocalizedText) attributeValues.get(3).getValue().getValue();
        LocalizedText description = (LocalizedText) attributeValues.get(4).getValue().getValue();
        UInteger writeMask = (UInteger) attributeValues.get(5).getValue().getValue();
        UInteger userWriteMask = (UInteger) attributeValues.get(6).getValue().getValue();

        Boolean isAbstract = (Boolean) attributeValues.get(7).getValue().getValue();

        return new UaObjectTypeNode(
            client,
            nodeId,
            nodeClass,
            browseName,
            displayName,
            description,
            writeMask,
            userWriteMask,
            isAbstract
        );
    }

    private UaReferenceTypeNode newReferenceTypeNode(NodeId nodeId, List<DataValue> attributeValues) {
        NodeClass nodeClass = NodeClass.from((Integer) attributeValues.get(1).getValue().getValue());

        Preconditions.checkArgument(
            nodeClass == NodeClass.ReferenceType,
            "expected NodeClass.ReferenceType, got NodeClass." + nodeClass
        );

        QualifiedName browseName = (QualifiedName) attributeValues.get(2).getValue().getValue();
        LocalizedText displayName = (LocalizedText) attributeValues.get(3).getValue().getValue();
        LocalizedText description = (LocalizedText) attributeValues.get(4).getValue().getValue();
        UInteger writeMask = (UInteger) attributeValues.get(5).getValue().getValue();
        UInteger userWriteMask = (UInteger) attributeValues.get(6).getValue().getValue();

        Boolean isAbstract = (Boolean) attributeValues.get(7).getValue().getValue();
        Boolean symmetric = (Boolean) attributeValues.get(8).getValue().getValue();
        LocalizedText inverseName = (LocalizedText) attributeValues.get(9).getValue().getValue();

        return new UaReferenceTypeNode(
            client,
            nodeId,
            nodeClass,
            browseName,
            displayName,
            description,
            writeMask,
            userWriteMask,
            isAbstract,
            symmetric,
            inverseName
        );
    }

    private UaVariableNode newVariableNode(NodeId nodeId, NodeId typeDefinitionId, List<DataValue> attributeValues) {
        NodeClass nodeClass = NodeClass.from((Integer) attributeValues.get(1).getValue().getValue());

        Preconditions.checkArgument(
            nodeClass == NodeClass.Variable,
            "expected NodeClass.Variable, got NodeClass." + nodeClass
        );

        QualifiedName browseName = (QualifiedName) attributeValues.get(2).getValue().getValue();
        LocalizedText displayName = (LocalizedText) attributeValues.get(3).getValue().getValue();
        LocalizedText description = (LocalizedText) attributeValues.get(4).getValue().getValue();
        UInteger writeMask = (UInteger) attributeValues.get(5).getValue().getValue();
        UInteger userWriteMask = (UInteger) attributeValues.get(6).getValue().getValue();

        DataValue value = attributeValues.get(7);
        NodeId dataType = (NodeId) attributeValues.get(8).getValue().getValue();
        Integer valueRank = (Integer) attributeValues.get(9).getValue().getValue();
        UInteger[] arrayDimensions = (UInteger[]) attributeValues.get(10).getValue().getValue();
        UByte accessLevel = (UByte) attributeValues.get(11).getValue().getValue();
        UByte userAccessLevel = (UByte) attributeValues.get(12).getValue().getValue();
        Double minimumSamplingInterval = (Double) attributeValues.get(13).getValue().getValue();
        Boolean historizing = (Boolean) attributeValues.get(14).getValue().getValue();

        VariableTypeManager.VariableNodeConstructor constructor = client.getVariableTypeManager()
            .getNodeConstructor(typeDefinitionId)
            .orElse(UaVariableNode::new);

        return constructor.apply(
            client,
            nodeId,
            nodeClass,
            browseName,
            displayName,
            description,
            writeMask,
            userWriteMask,
            value,
            dataType,
            valueRank,
            arrayDimensions,
            accessLevel,
            userAccessLevel,
            minimumSamplingInterval,
            historizing
        );
    }

    private UaVariableTypeNode newVariableTypeNode(NodeId nodeId, List<DataValue> attributeValues) {
        NodeClass nodeClass = NodeClass.from((Integer) attributeValues.get(1).getValue().getValue());

        Preconditions.checkArgument(
            nodeClass == NodeClass.VariableType,
            "expected NodeClass.VariableType, got NodeClass." + nodeClass
        );

        QualifiedName browseName = (QualifiedName) attributeValues.get(2).getValue().getValue();
        LocalizedText displayName = (LocalizedText) attributeValues.get(3).getValue().getValue();
        LocalizedText description = (LocalizedText) attributeValues.get(4).getValue().getValue();
        UInteger writeMask = (UInteger) attributeValues.get(5).getValue().getValue();
        UInteger userWriteMask = (UInteger) attributeValues.get(6).getValue().getValue();

        DataValue value = attributeValues.get(7);
        NodeId dataType = (NodeId) attributeValues.get(8).getValue().getValue();
        Integer valueRank = (Integer) attributeValues.get(9).getValue().getValue();
        UInteger[] arrayDimensions = (UInteger[]) attributeValues.get(10).getValue().getValue();
        Boolean isAbstract = (Boolean) attributeValues.get(11).getValue().getValue();

        return new UaVariableTypeNode(
            client,
            nodeId,
            nodeClass,
            browseName,
            displayName,
            description,
            writeMask,
            userWriteMask,
            value,
            dataType,
            valueRank,
            arrayDimensions,
            isAbstract
        );
    }

    private UaViewNode newViewNode(NodeId nodeId, List<DataValue> attributeValues) {
        NodeClass nodeClass = NodeClass.from((Integer) attributeValues.get(1).getValue().getValue());

        Preconditions.checkArgument(
            nodeClass == NodeClass.View,
            "expected NodeClass.View, got NodeClass." + nodeClass
        );

        QualifiedName browseName = (QualifiedName) attributeValues.get(2).getValue().getValue();
        LocalizedText displayName = (LocalizedText) attributeValues.get(3).getValue().getValue();
        LocalizedText description = (LocalizedText) attributeValues.get(4).getValue().getValue();
        UInteger writeMask = (UInteger) attributeValues.get(5).getValue().getValue();
        UInteger userWriteMask = (UInteger) attributeValues.get(6).getValue().getValue();

        Boolean containsNoLoops = (Boolean) attributeValues.get(7).getValue().getValue();
        UByte eventNotifier = (UByte) attributeValues.get(8).getValue().getValue();

        return new UaViewNode(
            client,
            nodeId,
            nodeClass,
            browseName,
            displayName,
            description,
            writeMask,
            userWriteMask,
            containsNoLoops,
            eventNotifier
        );
    }

    private Cache<NodeId, UaNode> buildCache() {
        return CacheBuilder.newBuilder()
            .expireAfterWrite(expireAfter)
            .maximumSize(maximumSize)
            .recordStats()
            .build();
    }

    public static class BrowseOptions {

        private final BrowseDirection browseDirection;
        private final NodeId referenceTypeId;
        private final boolean includeSubtypes;
        private final UInteger nodeClassMask;

        public BrowseOptions() {
            this(BrowseDirection.Forward, Identifiers.HierarchicalReferences, true, uint(0xFF));
        }

        public BrowseOptions(
            BrowseDirection browseDirection,
            NodeId referenceTypeId,
            boolean includeSubtypes,
            UInteger nodeClassMask
        ) {

            this.browseDirection = browseDirection;
            this.referenceTypeId = referenceTypeId;
            this.includeSubtypes = includeSubtypes;
            this.nodeClassMask = nodeClassMask;
        }

        public BrowseDirection getBrowseDirection() {
            return browseDirection;
        }

        public NodeId getReferenceTypeId() {
            return referenceTypeId;
        }

        public boolean isIncludeSubtypes() {
            return includeSubtypes;
        }

        public UInteger getNodeClassMask() {
            return nodeClassMask;
        }

        public BrowseOptions copy(Consumer<Builder> builderConsumer) {
            Builder builder = new Builder();
            builder.setReferenceType(referenceTypeId);
            builder.setIncludeSubtypes(includeSubtypes);
            builder.setNodeClassMask(nodeClassMask);
            builderConsumer.accept(builder);
            return builder.build();
        }

        public static class Builder {
            private BrowseDirection browseDirection = BrowseDirection.Forward;
            private NodeId referenceTypeId = Identifiers.HierarchicalReferences;
            private boolean includeSubtypes = true;
            private UInteger nodeClassMask = uint(0xFF);

            public Builder setBrowseDirection(BrowseDirection browseDirection) {
                this.browseDirection = browseDirection;
                return this;
            }

            public Builder setReferenceType(BuiltinReferenceType referenceType) {
                return setReferenceType(referenceType.getNodeId());
            }

            public Builder setReferenceType(NodeId referenceTypeId) {
                this.referenceTypeId = referenceTypeId;
                return this;
            }

            public Builder setIncludeSubtypes(boolean includeSubtypes) {
                this.includeSubtypes = includeSubtypes;
                return this;
            }

            public Builder setNodeClassMask(UInteger nodeClassMask) {
                this.nodeClassMask = nodeClassMask;
                return this;
            }

            public Builder setNodeClassMask(Set<NodeClass> nodeClasses) {
                int mask = 0;
                for (NodeClass nodeClass : nodeClasses) {
                    mask |= nodeClass.getValue();
                }
                return setNodeClassMask(uint(mask));
            }

            public BrowseOptions build() {
                return new BrowseOptions(browseDirection, referenceTypeId, includeSubtypes, nodeClassMask);
            }

        }
    }

}
