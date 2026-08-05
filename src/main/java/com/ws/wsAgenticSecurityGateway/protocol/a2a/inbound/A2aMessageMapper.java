package com.ws.wsAgenticSecurityGateway.protocol.a2a.inbound;

import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityResult;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps between the A2A wire (SDK spec types) and the gateway's neutral model at the inbound boundary: an
 * inbound {@link MessageSendParams} becomes the target skill name + arguments for a {@code Hop(SKILL)}, and a
 * governed {@link CapabilityResult} maps back into an A2A {@link Task}.
 *
 * <p>The A2A message names its target gateway skill in {@code message.metadata.skillId} (fallback:
 * {@code params.metadata.skillId}); its text parts concatenate into the {@code input} argument, and structured
 * arguments ride on {@code message.metadata.arguments}. Keeping this mapping in one place mirrors the MCP
 * inbound facade — the spine and PDP only ever see the neutral {@code Hop}/{@code CapabilityResult}.
 */
final class A2aMessageMapper {

    /** Metadata key on the A2A message naming the gateway skill to invoke. */
    static final String SKILL_ID_KEY = "skillId";
    /** Metadata key carrying structured skill arguments (a map). */
    static final String ARGUMENTS_KEY = "arguments";

    private A2aMessageMapper() {
    }

    /** The target skill name from the message metadata (then the params metadata), or {@code null} if unnamed. */
    static String resolveSkillName(MessageSendParams params) {
        Object skill = metaValue(params.message() != null ? params.message().metadata() : null, SKILL_ID_KEY);
        if (skill == null) {
            skill = metaValue(params.metadata(), SKILL_ID_KEY);
        }
        return skill != null ? String.valueOf(skill) : null;
    }

    /** Skill arguments: the concatenated text parts as {@code input}, plus any {@code arguments} metadata map. */
    static Map<String, Object> toArguments(MessageSendParams params) {
        Map<String, Object> args = new HashMap<>();
        Message message = params.message();
        if (message != null && message.parts() != null) {
            StringBuilder text = new StringBuilder();
            for (Part<?> part : message.parts()) {
                if (part instanceof TextPart tp && tp.text() != null && !tp.text().isBlank()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(tp.text());
                }
            }
            if (text.length() > 0) {
                args.put("input", text.toString());
            }
        }
        Object structured = metaValue(message != null ? message.metadata() : null, ARGUMENTS_KEY);
        if (structured instanceof Map<?, ?> map) {
            map.forEach((k, v) -> args.put(String.valueOf(k), v));
        }
        return args;
    }

    /** The governed result as an A2A {@link Task}: COMPLETED (or FAILED) with the summary as the agent's reply. */
    static Task toTask(MessageSendParams params, CapabilityResult result) {
        // contextId is required on a Task; if the inbound message carried none, mint a conversation context.
        String contextId = params.message() != null && params.message().contextId() != null
                ? params.message().contextId()
                : UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        TaskState state = result.error() ? TaskState.TASK_STATE_FAILED : TaskState.TASK_STATE_COMPLETED;
        Message reply = A2A.toAgentMessage(result.summary() != null ? result.summary() : "");
        TaskStatus status = new TaskStatus(state, reply, OffsetDateTime.now());
        return Task.builder()
                .id(taskId)
                .contextId(contextId)
                .status(status)
                .build();
    }

    private static Object metaValue(Map<String, Object> metadata, String key) {
        return metadata != null ? metadata.get(key) : null;
    }
}
