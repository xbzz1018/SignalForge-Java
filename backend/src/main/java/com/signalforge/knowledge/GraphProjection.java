package com.signalforge.knowledge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.platform.ApiException;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import java.util.*;
import org.neo4j.driver.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GraphProjection {
    private final Driver driver;
    private final JsonCodec json;
    public GraphProjection(JsonCodec json,@Value("${signalforge.neo4j-uri:bolt://127.0.0.1:47687}") String uri,
                           @Value("${signalforge.neo4j-password:}") String password) {
        this.json=json;
        driver=GraphDatabase.driver(uri,AuthTokens.basic("neo4j",password),Config.builder()
                .withConnectionTimeout(3,TimeUnit.SECONDS).withConnectionAcquisitionTimeout(4,TimeUnit.SECONDS)
                .withMaxTransactionRetryTime(3,TimeUnit.SECONDS).withMaxConnectionPoolSize(4).withTelemetryDisabled(true).build());
    }
    public void project(KnowledgeStore.Snapshot snapshot) {
        var graph=json.read(snapshot.graph(),KnowledgeEvidence.Graph.class);
        String project=snapshot.projectId().toString(), release=snapshot.id().toString();
        try (var session=driver.session()) {
            // Unique projection keys are project/release/entity scoped, including the release marker.
            session.run("CREATE CONSTRAINT sf_knowledge_node_key IF NOT EXISTS FOR (n:SFKnowledgeNode) REQUIRE n.key IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT sf_graph_release_key IF NOT EXISTS FOR (r:SFGraphRelease) REQUIRE r.key IS UNIQUE").consume();
            session.executeWrite(tx->{
                tx.run("MERGE (r:SFGraphRelease {key:$key}) SET r.projectId=$project,r.releaseId=$release,r.payloadHash=$hash",
                        Map.of("key",project+":"+release,"project",project,"release",release,"hash",snapshot.payloadHash())).consume();
                for (var node:graph.nodes()) {
                    tx.run("""
                        MERGE (n:SFKnowledgeNode {key:$key})
                        SET n.projectId=$project,n.releaseId=$release,n.entityId=$id,n.kind=$kind,n.name=$name
                        """,Map.of("key",project+":"+release+":"+node.id(),"project",project,"release",release,
                            "id",node.id(),"kind",node.kind(),"name",node.label())).consume();
                }
                for (var edge:graph.edges()) {
                    String type=switch(edge.type()) {
                        case "CLASSIFIED_AS" -> "CLASSIFIED_AS";
                        case "HAS_ANNOUNCEMENT" -> "HAS_ANNOUNCEMENT";
                        case "HAS_SOURCE_CLAIM" -> "HAS_SOURCE_CLAIM";
                        case "SUPPORTED_BY" -> "SUPPORTED_BY";
                        default -> throw new IllegalArgumentException("UNSUPPORTED_RELATION");
                    };
                    var evidence=graph.evidence().stream().filter(e->e.id().equals(edge.evidenceKey())).findFirst().orElseThrow();
                    tx.run("MATCH (a:SFKnowledgeNode {key:$source}),(b:SFKnowledgeNode {key:$target}) "+
                            "MERGE (a)-[r:"+type+" {key:$key}]->(b) SET r.edgeId=$id,r.evidenceKey=$evidence,r.snapshotHash=$hash,r.sourceUrl=$url",
                            Map.of("source",project+":"+release+":"+edge.source(),"target",project+":"+release+":"+edge.target(),
                                "key",project+":"+release+":"+edge.id(),"id",edge.id(),"evidence",edge.evidenceKey(),
                                "hash",snapshot.payloadHash(),"url",evidence.sourceUrl())).consume();
                }
                long count=tx.run("MATCH (n:SFKnowledgeNode {projectId:$project,releaseId:$release}) RETURN count(n) AS count",
                        Map.of("project",project,"release",release)).single().get("count").asLong();
                if (count!=graph.nodes().size()) throw new IllegalStateException("GRAPH_NODE_COUNT_MISMATCH");
                return true;
            });
        }
    }
    public KnowledgeEvidence.Graph read(KnowledgeStore.Snapshot snapshot) {
        if (!snapshot.state().equals("READY")) throw new ApiException(409,"GRAPH_NOT_READY","图谱投影尚未完成");
        var authoritative=json.read(snapshot.graph(),KnowledgeEvidence.Graph.class);
        try (var session=driver.session()) {
            return session.executeRead(tx->{
                var args=Map.<String,Object>of("project",snapshot.projectId().toString(),"release",snapshot.id().toString());
                var nodes=tx.run("MATCH (n:SFKnowledgeNode {projectId:$project,releaseId:$release}) RETURN n.entityId AS id,n.kind AS kind,n.name AS name ORDER BY n.key LIMIT 128",args)
                        .list(r->new KnowledgeEvidence.Node(r.get("id").asString(),r.get("kind").asString(),r.get("name").asString()));
                var edges=tx.run("""
                    MATCH (a:SFKnowledgeNode {projectId:$project,releaseId:$release})-[r:CLASSIFIED_AS|HAS_ANNOUNCEMENT|HAS_SOURCE_CLAIM|SUPPORTED_BY]->
                    (b:SFKnowledgeNode {projectId:$project,releaseId:$release})
                    RETURN r.edgeId AS id,a.entityId AS source,b.entityId AS target,type(r) AS type,r.evidenceKey AS evidence LIMIT 128
                    """,args).list(r->new KnowledgeEvidence.Edge(r.get("id").asString(),r.get("source").asString(),r.get("target").asString(),r.get("type").asString(),r.get("evidence").asString()));
                if (!new HashSet<>(nodes).equals(new HashSet<>(authoritative.nodes())) ||
                        !new HashSet<>(edges).equals(new HashSet<>(authoritative.edges())))
                    throw new ApiException(503,"GRAPH_PROJECTION_INCOMPLETE","图谱投影不完整，请重新采集以重建");
                return new KnowledgeEvidence.Graph(authoritative.releaseId(),authoritative.securityId(),authoritative.fetchedAt(),
                        authoritative.contentHash(),authoritative.snapshotHash(),nodes,edges,authoritative.evidence(),authoritative.warnings(),false,"NEO4J");
            });
        } catch (ApiException error) { throw error; }
        catch (RuntimeException error) { throw new ApiException(503,"GRAPH_UNAVAILABLE","图谱服务暂不可用，原始资料仍已保存"); }
    }
    @PreDestroy void close() { driver.close(); }
}
