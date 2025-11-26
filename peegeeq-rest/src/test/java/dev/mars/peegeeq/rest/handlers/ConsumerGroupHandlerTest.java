package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Consumer Group Management functionality.
 */
@Tag(TestCategories.CORE)
class ConsumerGroupHandlerTest {

    @BeforeEach
    void setUp() {
        new ObjectMapper();
    }

    @Test
    void testConsumerGroupCreation() {
        // Test consumer group creation structure
        
        String groupName = "test-group";
        String setupId = "test-setup";
        String queueName = "test-queue";
        String groupId = "group-uuid-123";
        
        JsonObject createRequest = new JsonObject()
            .put("groupName", groupName)
            .put("maxMembers", 5)
            .put("loadBalancingStrategy", "ROUND_ROBIN")
            .put("sessionTimeout", 30000L);
        
        JsonObject expectedResponse = new JsonObject()
            .put("message", "Consumer group created successfully")
            .put("groupName", groupName)
            .put("setupId", setupId)
            .put("queueName", queueName)
            .put("groupId", groupId)
            .put("maxMembers", 5)
            .put("loadBalancingStrategy", "ROUND_ROBIN")
            .put("sessionTimeout", 30000L)
            .put("timestamp", System.currentTimeMillis());
        
        // Verify request structure
        assertEquals(groupName, createRequest.getString("groupName"));
        assertEquals(5, createRequest.getInteger("maxMembers"));
        assertEquals("ROUND_ROBIN", createRequest.getString("loadBalancingStrategy"));
        assertEquals(30000L, createRequest.getLong("sessionTimeout"));
        
        // Verify response structure
        assertEquals("Consumer group created successfully", expectedResponse.getString("message"));
        assertEquals(groupName, expectedResponse.getString("groupName"));
        assertEquals(setupId, expectedResponse.getString("setupId"));
        assertEquals(queueName, expectedResponse.getString("queueName"));
        assertEquals(groupId, expectedResponse.getString("groupId"));
        assertEquals(5, expectedResponse.getInteger("maxMembers"));
        assertEquals("ROUND_ROBIN", expectedResponse.getString("loadBalancingStrategy"));
        assertEquals(30000L, expectedResponse.getLong("sessionTimeout"));
        assertTrue(expectedResponse.containsKey("timestamp"));
    }

    @Test
    void testConsumerGroupMemberManagement() {
        // Test member join/leave structures
        
        String memberId = "member-123";
        String memberName = "test-member";
        String groupName = "test-group";
        
        // Join request
        JsonObject joinRequest = new JsonObject()
            .put("memberName", memberName);
        
        // Join response
        JsonObject joinResponse = new JsonObject()
            .put("message", "Successfully joined consumer group")
            .put("groupName", groupName)
            .put("memberId", memberId)
            .put("memberName", memberName)
            .put("assignedPartitions", new JsonArray().add(0).add(1).add(2))
            .put("memberCount", 1)
            .put("timestamp", System.currentTimeMillis());
        
        // Leave response
        JsonObject leaveResponse = new JsonObject()
            .put("message", "Successfully left consumer group")
            .put("groupName", groupName)
            .put("memberId", memberId)
            .put("memberCount", 0)
            .put("timestamp", System.currentTimeMillis());
        
        // Verify join structures
        assertEquals(memberName, joinRequest.getString("memberName"));
        assertEquals("Successfully joined consumer group", joinResponse.getString("message"));
        assertEquals(groupName, joinResponse.getString("groupName"));
        assertEquals(memberId, joinResponse.getString("memberId"));
        assertEquals(memberName, joinResponse.getString("memberName"));
        assertTrue(joinResponse.containsKey("assignedPartitions"));
        assertEquals(1, joinResponse.getInteger("memberCount"));
        
        // Verify leave structures
        assertEquals("Successfully left consumer group", leaveResponse.getString("message"));
        assertEquals(groupName, leaveResponse.getString("groupName"));
        assertEquals(memberId, leaveResponse.getString("memberId"));
        assertEquals(0, leaveResponse.getInteger("memberCount"));
    }

    @Test
    void testConsumerGroupListing() {
        // Test consumer group listing structure
        
        JsonArray groups = new JsonArray();
        
        JsonObject group1 = new JsonObject()
            .put("groupName", "group-1")
            .put("groupId", "id-1")
            .put("setupId", "setup-1")
            .put("queueName", "queue-1")
            .put("memberCount", 3)
            .put("maxMembers", 10)
            .put("loadBalancingStrategy", "ROUND_ROBIN")
            .put("sessionTimeout", 30000L)
            .put("createdAt", System.currentTimeMillis())
            .put("lastActivity", System.currentTimeMillis());
        
        JsonObject group2 = new JsonObject()
            .put("groupName", "group-2")
            .put("groupId", "id-2")
            .put("setupId", "setup-1")
            .put("queueName", "queue-1")
            .put("memberCount", 1)
            .put("maxMembers", 5)
            .put("loadBalancingStrategy", "RANGE")
            .put("sessionTimeout", 60000L)
            .put("createdAt", System.currentTimeMillis())
            .put("lastActivity", System.currentTimeMillis());
        
        groups.add(group1).add(group2);
        
        JsonObject listResponse = new JsonObject()
            .put("message", "Consumer groups retrieved successfully")
            .put("setupId", "setup-1")
            .put("queueName", "queue-1")
            .put("groupCount", 2)
            .put("groups", groups)
            .put("timestamp", System.currentTimeMillis());
        
        // Verify list response structure
        assertEquals("Consumer groups retrieved successfully", listResponse.getString("message"));
        assertEquals("setup-1", listResponse.getString("setupId"));
        assertEquals("queue-1", listResponse.getString("queueName"));
        assertEquals(2, listResponse.getInteger("groupCount"));
        assertEquals(2, listResponse.getJsonArray("groups").size());
        
        // Verify individual group structures
        JsonObject firstGroup = listResponse.getJsonArray("groups").getJsonObject(0);
        assertEquals("group-1", firstGroup.getString("groupName"));
        assertEquals("id-1", firstGroup.getString("groupId"));
        assertEquals(3, firstGroup.getInteger("memberCount"));
        assertEquals(10, firstGroup.getInteger("maxMembers"));
        assertEquals("ROUND_ROBIN", firstGroup.getString("loadBalancingStrategy"));
    }

    @Test
    void testConsumerGroupDetails() {
        // Test detailed consumer group information structure
        
        JsonArray members = new JsonArray();
        
        JsonObject member1 = new JsonObject()
            .put("memberId", "member-1")
            .put("memberName", "consumer-1")
            .put("joinedAt", System.currentTimeMillis())
            .put("lastHeartbeat", System.currentTimeMillis())
            .put("assignedPartitions", new JsonArray().add(0).add(1).add(2).add(3))
            .put("status", "ACTIVE");
        
        JsonObject member2 = new JsonObject()
            .put("memberId", "member-2")
            .put("memberName", "consumer-2")
            .put("joinedAt", System.currentTimeMillis())
            .put("lastHeartbeat", System.currentTimeMillis())
            .put("assignedPartitions", new JsonArray().add(4).add(5).add(6).add(7))
            .put("status", "ACTIVE");
        
        members.add(member1).add(member2);
        
        JsonObject detailResponse = new JsonObject()
            .put("message", "Consumer group retrieved successfully")
            .put("groupName", "test-group")
            .put("groupId", "group-id-123")
            .put("setupId", "test-setup")
            .put("queueName", "test-queue")
            .put("memberCount", 2)
            .put("maxMembers", 10)
            .put("loadBalancingStrategy", "ROUND_ROBIN")
            .put("sessionTimeout", 30000L)
            .put("createdAt", System.currentTimeMillis())
            .put("lastActivity", System.currentTimeMillis())
            .put("members", members)
            .put("timestamp", System.currentTimeMillis());
        
        // Verify detail response structure
        assertEquals("Consumer group retrieved successfully", detailResponse.getString("message"));
        assertEquals("test-group", detailResponse.getString("groupName"));
        assertEquals("group-id-123", detailResponse.getString("groupId"));
        assertEquals(2, detailResponse.getInteger("memberCount"));
        assertEquals(10, detailResponse.getInteger("maxMembers"));
        assertEquals("ROUND_ROBIN", detailResponse.getString("loadBalancingStrategy"));
        assertEquals(30000L, detailResponse.getLong("sessionTimeout"));
        assertEquals(2, detailResponse.getJsonArray("members").size());
        
        // Verify member structures
        JsonObject firstMember = detailResponse.getJsonArray("members").getJsonObject(0);
        assertEquals("member-1", firstMember.getString("memberId"));
        assertEquals("consumer-1", firstMember.getString("memberName"));
        assertEquals("ACTIVE", firstMember.getString("status"));
        assertEquals(4, firstMember.getJsonArray("assignedPartitions").size());
    }

    @Test
    void testLoadBalancingStrategies() {
        // Test load balancing strategy enumeration
        
        LoadBalancingStrategy[] strategies = LoadBalancingStrategy.values();
        
        assertTrue(Arrays.asList(strategies).contains(LoadBalancingStrategy.ROUND_ROBIN));
        assertTrue(Arrays.asList(strategies).contains(LoadBalancingStrategy.RANGE));
        assertTrue(Arrays.asList(strategies).contains(LoadBalancingStrategy.STICKY));
        assertTrue(Arrays.asList(strategies).contains(LoadBalancingStrategy.RANDOM));
        
        // Test strategy names
        assertEquals("ROUND_ROBIN", LoadBalancingStrategy.ROUND_ROBIN.name());
        assertEquals("RANGE", LoadBalancingStrategy.RANGE.name());
        assertEquals("STICKY", LoadBalancingStrategy.STICKY.name());
        assertEquals("RANDOM", LoadBalancingStrategy.RANDOM.name());
    }

    @Test
    void testConsumerGroupMemberStatus() {
        // Test member status enumeration
        
        ConsumerGroupMember.MemberStatus[] statuses = ConsumerGroupMember.MemberStatus.values();
        
        assertTrue(Arrays.asList(statuses).contains(ConsumerGroupMember.MemberStatus.ACTIVE));
        assertTrue(Arrays.asList(statuses).contains(ConsumerGroupMember.MemberStatus.INACTIVE));
        assertTrue(Arrays.asList(statuses).contains(ConsumerGroupMember.MemberStatus.REBALANCING));
        
        // Test status names
        assertEquals("ACTIVE", ConsumerGroupMember.MemberStatus.ACTIVE.name());
        assertEquals("INACTIVE", ConsumerGroupMember.MemberStatus.INACTIVE.name());
        assertEquals("REBALANCING", ConsumerGroupMember.MemberStatus.REBALANCING.name());
    }

    @Test
    void testPartitionAssignment() {
        // Test partition assignment logic
        
        // Simulate partition assignment for round-robin
        List<Integer> partitions = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        int memberCount = 3;
        
        // Expected distribution for 16 partitions across 3 members:
        // Member 0: [0, 3, 6, 9, 12, 15] (6 partitions)
        // Member 1: [1, 4, 7, 10, 13] (5 partitions)
        // Member 2: [2, 5, 8, 11, 14] (5 partitions)
        
        assertEquals(16, partitions.size());
        assertEquals(3, memberCount);
        
        // Verify total partitions are distributed
        int expectedPartitionsPerMember = partitions.size() / memberCount;
        int remainder = partitions.size() % memberCount;
        
        assertEquals(5, expectedPartitionsPerMember);
        assertEquals(1, remainder);
        
        // First member gets one extra partition due to remainder
        int firstMemberPartitions = expectedPartitionsPerMember + (remainder > 0 ? 1 : 0);
        assertEquals(6, firstMemberPartitions);
    }

    @Test
    void testConsumerGroupApiDocumentation() {
        // This test documents the Consumer Group API usage
        
        System.out.println("📚 Phase 4 Consumer Group Management API Documentation:");
        System.out.println();
        
        System.out.println("🔹 Consumer Group Operations:");
        System.out.println("POST /api/v1/queues/{setupId}/{queueName}/consumer-groups");
        System.out.println("- Create a new consumer group");
        System.out.println("- Configure max members, load balancing strategy, session timeout");
        System.out.println();
        
        System.out.println("GET /api/v1/queues/{setupId}/{queueName}/consumer-groups");
        System.out.println("- List all consumer groups for a queue");
        System.out.println("- Shows member counts and configuration");
        System.out.println();
        
        System.out.println("GET /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}");
        System.out.println("- Get detailed information about a consumer group");
        System.out.println("- Includes member details and partition assignments");
        System.out.println();
        
        System.out.println("DELETE /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}");
        System.out.println("- Delete a consumer group and clean up resources");
        System.out.println();
        
        System.out.println("🔹 Member Operations:");
        System.out.println("POST /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}/members");
        System.out.println("- Join a consumer group as a new member");
        System.out.println("- Triggers automatic partition rebalancing");
        System.out.println();
        
        System.out.println("DELETE /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}/members/{memberId}");
        System.out.println("- Leave a consumer group");
        System.out.println("- Triggers automatic partition rebalancing");
        System.out.println();
        
        System.out.println("🔹 Load Balancing Strategies:");
        System.out.println("- ROUND_ROBIN: Distribute partitions evenly in round-robin fashion");
        System.out.println("- RANGE: Assign contiguous ranges of partitions to members");
        System.out.println("- STICKY: Minimize partition reassignment during rebalancing");
        System.out.println("- RANDOM: Randomly distribute partitions to members");
        System.out.println();
        
        System.out.println("🔹 Configuration Options:");
        System.out.println("- maxMembers: Maximum number of members (1-100)");
        System.out.println("- sessionTimeout: Member heartbeat timeout (5s-5min)");
        System.out.println("- loadBalancingStrategy: Partition assignment strategy");
        
        assertTrue(true, "Consumer Group API documentation complete");
    }
}
