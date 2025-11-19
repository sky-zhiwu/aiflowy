
package tech.aiflowy.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.client.OkHttpClientUtil;
import com.agentsflex.core.llm.functions.Function;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.llm.response.FunctionCaller;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.prompt.HistoriesPrompt;
import com.agentsflex.core.prompt.ToolPrompt;
import com.agentsflex.core.react.ReActAgent;
import com.agentsflex.core.react.ReActAgentListener;
import com.agentsflex.core.react.ReActStep;
import com.agentsflex.core.util.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alicp.jetcache.Cache;
import com.mybatisflex.core.query.QueryWrapper;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.aiflowy.ai.entity.*;
import tech.aiflowy.ai.enums.BotMessageTypeEnum;
import tech.aiflowy.ai.mapper.AiBotConversationMessageMapper;
import tech.aiflowy.ai.message.MultimodalMessageBuilder;
import tech.aiflowy.ai.message.NormalMessageBuilder;
import tech.aiflowy.ai.service.*;
import tech.aiflowy.ai.utils.AiBotChatUtil;
import tech.aiflowy.ai.utils.AiBotMessageIframeMemory;
import tech.aiflowy.common.ai.ChatManager;
import tech.aiflowy.common.ai.MySseEmitter;
import tech.aiflowy.common.audio.core.AudioServiceManager;
import tech.aiflowy.common.domain.Result;
import tech.aiflowy.common.satoken.util.SaTokenUtil;
import tech.aiflowy.common.util.Maps;
import tech.aiflowy.common.util.StringUtil;
import tech.aiflowy.common.web.controller.BaseCurdController;
import tech.aiflowy.common.web.exceptions.BusinessException;
import tech.aiflowy.common.web.jsonbody.JsonBody;
import tech.aiflowy.system.mapper.SysApiKeyMapper;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;

/**
 * 控制层。
 *
 * @author michael
 * @since 2024-08-23
 */
@RestController
@RequestMapping("/api/v1/aiBot")
public class AiBotController extends BaseCurdController<AiBotService, AiBot> {

    private final AiLlmService aiLlmService;
    private final AiBotWorkflowService aiBotWorkflowService;
    private final AiBotKnowledgeService aiBotKnowledgeService;
    private final AiBotMessageService aiBotMessageService;
    @Resource
    private SysApiKeyMapper aiBotApiKeyMapper;
    @Resource
    private AiBotConversationMessageService aiBotConversationMessageService;
    @Resource
    private AiBotConversationMessageMapper aiBotConversationMessageMapper;
    @Resource
    private AiBotService aiBotService;
    @Autowired
    @Qualifier("defaultCache") // 指定 Bean 名称
    private Cache<String, Object> cache;
    @Resource
    private AudioServiceManager audioServiceManager;

    private static final Logger logger = LoggerFactory.getLogger(AiBotController.class);

    public AiBotController(AiBotService service, AiLlmService aiLlmService, AiBotWorkflowService aiBotWorkflowService,
                           AiBotKnowledgeService aiBotKnowledgeService, AiBotMessageService aiBotMessageService) {
        super(service);
        this.aiLlmService = aiLlmService;
        this.aiBotWorkflowService = aiBotWorkflowService;
        this.aiBotKnowledgeService = aiBotKnowledgeService;
        this.aiBotMessageService = aiBotMessageService;
    }

    @Resource
    private AiBotPluginsService aiBotPluginsService;
    @Resource
    private AiPluginToolService aiPluginToolService;

    @PostMapping("updateOptions")
    @SaCheckPermission("/api/v1/aiBot/save")
    public Result updateOptions(@JsonBody("id")
                                BigInteger id, @JsonBody("options")
                                Map<String, Object> options) {
        AiBot aiBot = service.getById(id);
        Map<String, Object> existOptions = aiBot.getOptions();
        if (existOptions == null) {
            existOptions = new HashMap<>();
        }
        if (options != null) {
            existOptions.putAll(options);
        }
        aiBot.setOptions(existOptions);
        service.updateById(aiBot);
        return Result.success();
    }

    @PostMapping("updateLlmOptions")
    @SaCheckPermission("/api/v1/aiBot/save")
    public Result updateLlmOptions(@JsonBody("id")
                                   BigInteger id, @JsonBody("llmOptions")
                                   Map<String, Object> llmOptions) {
        AiBot aiBot = service.getById(id);
        Map<String, Object> existLlmOptions = aiBot.getLlmOptions();
        if (existLlmOptions == null) {
            existLlmOptions = new HashMap<>();
        }
        if (llmOptions != null) {
            existLlmOptions.putAll(llmOptions);
        }
        aiBot.setLlmOptions(existLlmOptions);
        service.updateById(aiBot);
        return Result.success();
    }

    @PostMapping("voiceInput")
    @SaIgnore
    public Result voiceInput(@RequestParam("audio")
                             MultipartFile audioFile) {

        String recognize = null;
        try {
            recognize = audioServiceManager.audioToText(audioFile.getInputStream());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return Result.success(recognize);
    }

    /**
     * 当前系统用户调用对话
     *
     * @param prompt
     * @param botId
     * @param sessionId
     * @param isExternalMsg
     * @param response
     * @return
     */
    @PostMapping("chat")
    @SaIgnore
    public SseEmitter chat(@JsonBody(value = "prompt", required = true)
                           String prompt, @JsonBody(value = "botId", required = true)
                           BigInteger botId, @JsonBody(value = "sessionId", required = true)
                           String sessionId, @JsonBody(value = "isExternalMsg")
                           int isExternalMsg, @JsonBody(value = "tempUserId")
                           String tempUserId, @JsonBody(value = "fileList")
                           List<String> fileList, HttpServletResponse response) {
        response.setContentType("text/event-stream");


        if (!StringUtils.hasLength(prompt)) {
            throw new BusinessException("提示词不能为空！");
        }


        AiBot aiBot = service.getById(botId);

        if (aiBot == null) {
            return ChatManager.getInstance().sseEmitterForContent(JSON.toJSONString(Maps.of("content", "机器人不存在")));
        }

        boolean login = StpUtil.isLogin();

        if (!login && !aiBot.isAnonymousEnabled()) {
            return ChatManager.getInstance().sseEmitterForContent(JSON.toJSONString(Maps.of("content", "此bot不支持匿名访问")));

        }

        Map<String, Object> llmOptions = aiBot.getLlmOptions();
        String systemPrompt = llmOptions != null ?
                (String) llmOptions.get("systemPrompt") == null || !StringUtils.hasLength((String) llmOptions.get("systemPrompt")) ? "你是一个AI助手，请根据用户的问题给出清晰、准确的回答。" : (String) llmOptions.get("systemPrompt")
                : null;
        AiLlm aiLlm = aiLlmService.getById(aiBot.getLlmId());

        if (aiLlm == null) {
            return ChatManager.getInstance().sseEmitterForContent(JSON.toJSONString(Maps.of("content", "LLM不存在")));
        }

        Llm llm = aiLlm.toLlm();

        if (llm == null) {
            return ChatManager.getInstance().sseEmitterForContent(JSON.toJSONString(Maps.of("content", "LLM获取为空")));
        }
        final HistoriesPrompt historiesPrompt = new HistoriesPrompt();
        if (llmOptions != null && llmOptions.get("maxMessageCount") != null) {
            Object maxMessageCount = llmOptions.get("maxMessageCount");
            historiesPrompt.setMaxAttachedMessageCount(Integer.parseInt(String.valueOf(maxMessageCount)));
        }
        if (StringUtils.hasLength(systemPrompt)) {
            historiesPrompt.setSystemMessage(SystemMessage.of(systemPrompt));
        }
        if (StpUtil.isLogin()) {
            AiBotMessageMemory memory = new AiBotMessageMemory(botId, SaTokenUtil.getLoginAccount().getId(), sessionId,
                    isExternalMsg, aiBotMessageService, aiBotConversationMessageMapper, aiBotConversationMessageService);
            historiesPrompt.setMemory(memory);

        } else {
            AiBotMessageIframeMemory memory = new AiBotMessageIframeMemory(botId, tempUserId, sessionId, cache,
                    aiBotConversationMessageService, prompt);
            historiesPrompt.setMemory(memory);

        }
        boolean needEnglishName = AiBotChatUtil.needEnglishName(llm);

        MySseEmitter emitter = new MySseEmitter(1000 * 60 * 300L);
        List<Function> functions = null;
        try {
            functions = buildFunctionList(Maps.of("botId", botId).set("needEnglishName", needEnglishName));
        } catch (Exception throwables) {
            logger.error("构建工具列表时报错：", throwables);
            return ChatManager.getInstance()
                    .sseEmitterForContent(JSON.toJSONString(Maps.of("content", "大模型调用出错，请检查配置后重试！")));
        }

        ChatOptions chatOptions = getChatOptions(llmOptions);

        aiBotConversationMessageService.needRefreshConversationTitle(sessionId, prompt, llm, botId, isExternalMsg);
        try {
            emitter.send(SseEmitter.event().name("refreshSession").data(JSON.toJSONString(Maps.of("content", ""))));
        } catch (IOException e) {
            logger.error("创建会话报错", e);
        }

        final OkHttpClient.Builder[] builder = {new OkHttpClient.Builder()};
        builder[0].connectTimeout(Duration.ofSeconds(30));
        builder[0].readTimeout(Duration.ofMinutes(20));
        OkHttpClientUtil.setOkHttpClientBuilder(builder[0]);

        final String messageSessionId = UUID.randomUUID().toString().replace("-", "");
        final String connectId = UUID.randomUUID().toString();
        StringBuilder finalAnswerContentBuffer = new StringBuilder();

        Map<String, Object> options = aiBot.getOptions();
        boolean voiceEnabled = options != null && options.get("voiceEnabled") != null && (boolean) options.get(
                "voiceEnabled");

        WebSocket finalWebSocket = null;

        boolean reActEnabled = options != null && options.get("reActModeEnabled") != null && (Boolean) options.get(
                "reActModeEnabled");

        JSONObject obj = new JSONObject();
        obj.put("status", "START");
        obj.put("messageId", messageSessionId);
        emitter.send(obj.toJSONString());

        if (!reActEnabled) {
            // 普通模式
            return normalChat(emitter, aiLlm, functions, prompt, fileList, historiesPrompt, chatOptions, finalWebSocket,
                    finalAnswerContentBuffer, messageSessionId, voiceEnabled, builder, sessionId);
        } else {
            // ReAct 模式
            return reActChat(emitter, aiLlm, functions, prompt, fileList, historiesPrompt, chatOptions, finalWebSocket,
                    finalAnswerContentBuffer, messageSessionId, voiceEnabled, builder);
        }

    }

    /**
     * ReAct 模式对话
     */
    private SseEmitter reActChat(
            MySseEmitter emitter,
            AiLlm aiLlm,
            List<Function> functions,
            String prompt,
            List<String> fileList,
            HistoriesPrompt historiesPrompt,
            ChatOptions chatOptions,
            WebSocket finalWebSocket,
            StringBuilder finalAnswerContentBuffer,
            String messageSessionId,
            boolean voiceEnabled,
            OkHttpClient.Builder[] builder

    ) {

        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        Llm llm = aiLlm.toLlm();

        ReActAgent reActAgent = new ReActAgent(llm, functions, prompt, historiesPrompt);
        reActAgent.setChatOptions(chatOptions);

        String promptTemplate = "你是一个 ReAct Agent,结合 Reasoning（推理）和 Action（行动）来解决问题。\n" +
                "但在处理用户问题时，请首先判断：\n" +
                "1. 如果问题可以通过你的常识或已有知识直接回答 → 请忽略 ReAct 框架，直接输出自然语言回答。\n" +
                "2. 如果问题需要调用特定工具才能解决（如查询、计算、获取外部信息等）→ 请严格按照 ReAct 格式响应。\n\n" +
                "如果你选择使用 ReAct 模式，请遵循以下格式：\n" +
                "Thought: 描述你对当前问题的理解，包括已知信息和缺失信息，说明你下一步将采取什么行动及其原因。\n" +
                "Action: 从下方列出的工具中选择一个合适的工具，仅输出工具名称，不得虚构。\n" +
                "Action Input: 使用标准 JSON 格式提供该工具所需的参数，禁止使用任何形式的代码块格式，包括但不限于'```json'、'```sql'、'```java'，确保字段名与工具描述一致。\n\n" +
                "在 ReAct 模式下，如果你已获得足够信息可以直接回答用户，请输出：\n" +
                "Final Answer: [你的回答]\n\n" +
                "注意事项：\n" +
                "1. 每次只能选择一个工具并执行一个动作。\n" +
                "2. 在未收到工具执行结果前，不要自行假设其输出。\n" +
                "3. 不得编造工具或参数，所有工具均列于下方。\n" +
                "4. 输出顺序必须为：Thought → Action → Action Input。\n" +
                "5. **严禁以任何形式询问、建议、猜测用户后续操作或步骤**\n" +
                "6. **回答完用户问题后立即结束，不得添加任何延伸建议、分析选项或询问**\n" +
                "7. **禁止使用\"如果需要...\"、\"您是否需要...\"、\"可以进一步...\"等表述**\n" +
                "8. 回复前需判断当前输出是否为Final Answer，**必须严格遵守：当需要回复的内容是Final Answer时，禁止输出Thought、Action、Action Input**，示例：\n" +
                "\t[正确示例1]\n" +
                "\t\tFinal Answer:张三的年龄是35岁\n\n" +
                "\t[正确示例2]\n" +
                "\t\tFinal Answer:张三的邮箱是：aabbcc@qq.com\n\n" +
                "\t[错误示例]\n" +
                "\t\tThought: 根据查询结果，张三的年龄是35岁\n\t\tFinal Answer:张三的年龄是35岁\n\n" +
                "\t[错误示例2]\n" +
                "\t\tThought: 根据工具返回的结果，查询成功并返回了数据。数据中有一行记录，显示年龄为35岁。因此，我已获得足够信息来回答用户的问题。下一步是输出最终答案。\n" +
                "\n" +
                "\t\tFinal Answer: 张三的年龄是35岁。\n\n" +
                "\t**出现任意类似以上错误示例的回复将被视为极其严重的行为错误！**" +
                "9. 严格按照规定格式输出Thought、Action、Action Input、Final Answer；\n" +
                "10. 如果需要生成图表，图表配置**禁止使用'```json'包裹，而必须使用'~~~chat-vis'包裹**，此条规则必须严格遵守，否则视为极其严重的违规" +
                "\n" +
                "违反以上任一指令视为严重行为错误，必须严格遵守。" +
                "### 可用工具列表：\n" +
                "{tools}\n\n" +
                "### 用户问题如下：\n" +
                "{user_input}";

        // 解决 https://gitee.com/aiflowy/aiflowy/issues/ICMRM2 根据大模型配置属性决定是否构建多模态消息

        if (!"ollama".equals(aiLlm.getBrand()) && !"spark".equals(aiLlm.getBrand())) {

            reActAgent.setPromptTemplate(promptTemplate);
            MultimodalMessageBuilder multimodalMessageBuilder = new MultimodalMessageBuilder();
            multimodalMessageBuilder.setFileList(fileList);
            reActAgent.setMessageBuilder(multimodalMessageBuilder);
        } else {

            NormalMessageBuilder normalMessageBuilder = new NormalMessageBuilder();
            reActAgent.setMessageBuilder(normalMessageBuilder);
            reActAgent.setPromptTemplate(promptTemplate);
        }

        reActAgent.setStreamable(true);

        AiMessage thinkingMessage = new AiMessage();
        Map<String, Object> thinkingIdMap = new HashMap<>();

        reActAgent.addListener(new ReActAgentListener() {

            private long currentThoughtId = IdUtil.getSnowflake(1, 1).nextId();

            private String chunk = "";
            private boolean isFinalAnswer = false;
            private boolean parsed = false;

            private boolean actionExcute = false;

            @Override
            public void onChatResponseStream(ChatContext context, AiMessageResponse response) {

                String reasoningContent = response.getMessage().getReasoningContent();
                String fullReasoningContent = response.getMessage().getFullReasoningContent();
                String content = response.getMessage().getContent();
                if (content == null)
                    content = "";

                if (StringUtils.hasLength(reasoningContent)) {
                    if (thinkingIdMap.get("id") == null) {
                        thinkingIdMap.put("id", IdUtil.getSnowflake(1, 1).nextId());
                    }
                    thinkingIdMap.put("chainTitle", "🧠 思考");

                    thinkingMessage.setContent(reasoningContent);
                    thinkingMessage.setFullContent(fullReasoningContent);
                    thinkingMessage.setMetadataMap(thinkingIdMap);

                    try {
                        emitter.send(SseEmitter.event().name("thinking").data(JSON.toJSONString(thinkingMessage)));
                    } catch (IOException e) {
                        throw new BusinessException("发送思考事件报错");
                    }

                } else {

                    // 累积内容用于判断类型
                    chunk += content;

                    // 分阶段判断：先用少量内容做初步判断，再用更多内容做确认
                    if (!parsed) {
                        String lowerChunk = chunk.toLowerCase();

                        // 第一阶段：如果内容足够少且能明确判断Final Answer，立即处理
                        if (chunk.trim().length() >= 12) {
                            if (lowerChunk.trim().startsWith("final answer:") || lowerChunk.trim()
                                    .startsWith("final answer :") || lowerChunk.trim().startsWith("final answer ")) {
                                isFinalAnswer = true;
                                // 处理Final Answer，去掉"Final Answer:"前缀
                                String finalContent = chunk.replaceFirst("(?i)final answer\\s*:", "").trim();
                                AiMessage message = new AiMessage();
                                message.setContent(finalContent);
                                logger.info("发送final answer:" + finalContent);
                                emitter.send(JSON.toJSONString(message));
                                message.setMetadataMap(Maps.of("messageSessionId", messageSessionId));
                                finalAnswerContentBuffer.append(finalContent);

                                parsed = true;
                                return;
                            }
                        }

                        // 第二阶段：积累足够内容来判断ReAct格式
                        if (chunk.trim().length() >= 50) {
                            // 检查是否包含ReAct的关键模式
                            boolean hasReActPattern = lowerChunk.contains("thought:") || lowerChunk.contains("thought ")
                                    || lowerChunk.matches(".*\\d+\\..*thought.*") ||  // 匹配 "1. xxx Thought" 模式
                                    lowerChunk.contains("思考：") || lowerChunk.contains("分析：");

                            if (hasReActPattern) {
                                isFinalAnswer = false;
                                // 发送Thought事件
                                AiMessage thoughtMessage = new AiMessage();
                                thoughtMessage.setContent(chunk);
                                thoughtMessage.setFullContent(chunk);
                                thoughtMessage.setMetadataMap(Maps.of("showContent", chunk)
                                        .set("type", BotMessageTypeEnum.REACT_THINKING.getValue())
                                        .set("chainTitle", "💭 思路")
                                        .set("chainContent", chunk)
                                        .set("id", currentThoughtId + ""));

                                try {
                                    emitter.send(SseEmitter.event()
                                            .name("thought")
                                            .data(JSON.toJSONString(thoughtMessage)));
                                } catch (IOException e) {
                                    throw new BusinessException("发送思路事件报错");
                                }
                                parsed = true;
                                return;
                            }
                        }

                        // 第三阶段：如果累积内容过多仍无法判断，默认当作Final Answer
                        if (chunk.trim().length() >= 100) {
                            isFinalAnswer = true;
                            AiMessage message = new AiMessage();
                            message.setContent(chunk);
                            emitter.send(JSON.toJSONString(message));
                            logger.info("发送final answer:" + chunk);

                            parsed = true;
                            return;
                        }
                    }

                    // 如果已经解析过类型，继续按照对应类型处理后续内容
                    if (parsed) {
                        AiMessage aiMessage = new AiMessage();
                        if (isFinalAnswer) {
                            // Final Answer模式：直接发送内容
                            aiMessage.setContent(content);
                            aiMessage.setMetadataMap(Maps.of("messageSessionId", messageSessionId));
                            emitter.send(JSON.toJSONString(aiMessage));
                            logger.info("发送final answer:" + content);
                            finalAnswerContentBuffer.append(content);

                        } else {
                            // Thought模式：发送thought事件
                            aiMessage.setFullContent(content);
                            aiMessage.setContent(content);
                            aiMessage.setMetadataMap(Maps.of("showContent", content)
                                    .set("type", BotMessageTypeEnum.REACT_THINKING.getValue())
                                    .set("chainTitle", "💭 思路")
                                    .set("chainContent", content)
                                    .set("id", currentThoughtId + ""));

                            try {
                                emitter.send(SseEmitter.event().name("thought").data(JSON.toJSONString(aiMessage)));
                            } catch (IOException e) {
                                throw new BusinessException("发送思路事件报错");
                            }
                        }
                    }
                }

            }

            @Override
            public void onFinalAnswer(String finalAnswer) {
                logger.info("onFinalAnswer,{}", finalAnswer);

                RequestContextHolder.setRequestAttributes(sra, true);

                emitter.complete();

            }

            @Override
            public void onStepParseError(String content) {
                logger.error("onStepParseError,content:{}", content);
                emitter.sendAndComplete(JSON.toJSONString(Maps.of("content", "\n解析工具调用步骤失败....请重试")));
            }

            @Override
            public void onActionNotMatched(ReActStep step, List<Function> functions) {
                logger.error("onActionNotMatched,{}", functions);
                emitter.sendAndComplete(JSON.toJSONString(Maps.of("content", "没有找到可用工具....")));
            }

            @Override
            public void onActionInvokeError(Exception e) {
                logger.error("onActionError", e);
                AiMessage aiMessage = new AiMessage();
                aiMessage.setFullContent("工具执行过程出现异常....正在尝试解决....");
                aiMessage.setContent("工具执行过程出现异常....正在尝试解决....");
                aiMessage.setMetadataMap(Maps.of("showContent", "工具执行过程出现异常....正在尝试解决....")
                        .set("type", BotMessageTypeEnum.REACT_THINKING.getValue())
                        .set("chainTitle", "💭 思路")
                        .set("chainContent", "工具执行过程出现异常....正在尝试解决....")
                        .set("id", IdUtil.getSnowflake(1, 1).nextId() + ""));

                try {
                    emitter.send(SseEmitter.event().name("thought").data(JSON.toJSONString(aiMessage)));
                } catch (IOException ex) {
                    throw new BusinessException("发送思路事件报错");
                }
            }

            @Override
            public void onNonActionResponseStream(ChatContext context) {
                logger.info("onNonActionResponseStream");
                RequestContextHolder.setRequestAttributes(sra, true);

                if (actionExcute) {
                    logger.info("执行了 action ，结果已在其他 hook 中输出，跳过");
                    emitter.complete();
                    return;
                }

                String fullContent = context.getLastAiMessage().getFullContent();

                AiMessage message = new AiMessage();
                message.setContent(fullContent);
                message.setMetadataMap(Maps.of("messageSessionId", messageSessionId));
                emitter.sendAndComplete(JSON.toJSONString(message));
                finalAnswerContentBuffer.append(fullContent);
            }

            @Override
            public void onError(Exception error) {
                logger.error("onError:", error);

                AiMessage aiMessage = new AiMessage();
                aiMessage.setContent("大模型调用出错，请检查配置");
                boolean hasUnsupportedApiError = containsUnsupportedApiError(error.getMessage());
                if (hasUnsupportedApiError) {
                    String errMessage = error.getMessage()
                            + "\n**以下是 AIFlowy 提供的可查找当前错误的方向**\n**1: 在 AIFlowy 中，Bot 对话需要大模型携带 function_calling 功能**"
                            + "\n**2: 请查看当前模型是否支持 function_calling 调用？**";
                    aiMessage.setContent(errMessage);
                }
                emitter.send(JSON.toJSONString(aiMessage));
                emitter.completeWithError(error);
            }

            @Override
            public void onActionStart(ReActStep step) {

                logger.info("onActionStart");

                // 重置状态
                builder[0] = new OkHttpClient.Builder();
                builder[0].connectTimeout(Duration.ofSeconds(30));
                builder[0].readTimeout(Duration.ofMinutes(20));
                OkHttpClientUtil.setOkHttpClientBuilder(builder[0]);
                actionExcute = true;

                currentThoughtId = IdUtil.getSnowflake(1, 1).nextId();
                parsed = false;
                isFinalAnswer = false;
                chunk = "";

                if (StringUtils.hasLength(thinkingMessage.getFullContent())) {
                    thinkingMessage.setFullContent("thinking:" + thinkingMessage.getFullContent());
                    historiesPrompt.addMessage(thinkingMessage);
                    thinkingMessage.setFullContent("");
                    thinkingMessage.setContent("");
                    thinkingMessage.setMetadataMap(null);
                    thinkingIdMap.put("id", null);
                    thinkingIdMap.put("type", BotMessageTypeEnum.NORMAL.getValue());
                }

                RequestContextHolder.setRequestAttributes(sra, true);

                AiMessage toolCallMessage = new AiMessage();
                toolCallMessage.setContent(step.getAction());
                toolCallMessage.setFullContent(step.getAction());
                toolCallMessage.setMetadataMap(Maps.of("showContent", toolCallMessage.getContent())
                        .set("type", BotMessageTypeEnum.REACT_THINKING.getValue())
                        .set("chainTitle", "\n\n\uD83D\uDCCB 调用工具中..." + "\n\n")
                        .set("chainContent", step.getAction())
                        .set("id", IdUtil.getSnowflake(1, 1).nextId() + ""));
                historiesPrompt.addMessage(toolCallMessage);
                try {
                    emitter.send(SseEmitter.event().name("toolCalling").data(JSON.toJSONString(toolCallMessage)));
                } catch (IOException e) {
                    throw new BusinessException("发送工具调用事件报错");
                }

                String actionInput = step.getActionInput();
                logger.info("onActionStart:{}", actionInput);
            }

            @Override
            public void onActionEnd(ReActStep step, Object result) {
                logger.info("onActionEnd----> step:{},result:{}", step, result);

                currentThoughtId = IdUtil.getSnowflake(1, 1).nextId();
                parsed = false;
                isFinalAnswer = false;
                chunk = "";

                AiMessage aiMessage = new AiMessage();
                aiMessage.setFullContent("\uD83D\uDD0D 调用结果:" + result + "\n\n");
                aiMessage.setContent("\uD83D\uDD0D 调用结果:" + result + "\n\n");
                aiMessage.setMetadataMap(Maps.of("showContent", aiMessage.getContent())
                        .set("type", BotMessageTypeEnum.TOOL_RESULT.getValue())
                        .set("chainTitle", "\uD83D\uDD0D 调用结果")
                        .set("chainContent", result.toString())
                        .set("id", IdUtil.getSnowflake(1, 1).nextId() + ""));
                historiesPrompt.addMessage(aiMessage);
                try {
                    emitter.send(SseEmitter.event().name("callResult").data(JSON.toJSONString(aiMessage)));
                } catch (IOException e) {
                    throw new BusinessException("发送工具调用结果事件报错");
                }
            }

        });

        reActAgent.run();
        return emitter;
    }

    private SseEmitter normalChat(
            MySseEmitter emitter,
            AiLlm aiLlm,
            List<Function> functions,
            String prompt,
            List<String> fileList,
            HistoriesPrompt historiesPrompt,
            ChatOptions chatOptions,
            WebSocket finalWebSocket,
            StringBuilder finalAnswerContentBuffer,
            String messageSessionId,
            boolean voiceEnabled,
            OkHttpClient.Builder[] builder,
            String sessionId
    ) {
        Llm llm = aiLlm.toLlm();
        HumanMessage humanMessage = new HumanMessage(prompt);

        if (!"ollama".equals(aiLlm.getBrand()) && !"spark".equals(aiLlm.getBrand())) {

            // 构建多模态消息

            humanMessage.setMetadataMap(
                    Maps.of("type", BotMessageTypeEnum.USER_INPUT.getValue())
                            .set("fileList", fileList)
                            .set("user_input", prompt)
            );

        }


        humanMessage.addFunctions(functions);
        historiesPrompt.addMessage(humanMessage);


        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        final Boolean[] needClose = {true};

        AiMessage thinkingMessage = new AiMessage();
        Map<String, Object> thinkingIdMap = new HashMap<>();

        llm.chatStream(historiesPrompt, new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                try {
                    RequestContextHolder.setRequestAttributes(sra, true);
                    if (response != null) {
                        // 检查是否需要触发 Function Calling
                        logger.info("是否需要调用function calling:{}", response.getFunctionCallers() != null && CollectionUtil
                                .hasItems(response.getFunctionCallers()));
                        if (response.getFunctionCallers() != null && CollectionUtil.hasItems(response
                                .getFunctionCallers())) {
                            needClose[0] = false;
                            functionCall(
                                    response,
                                    emitter,
                                    historiesPrompt,
                                    llm,
                                    sessionId,
                                    finalAnswerContentBuffer,
                                    chatOptions,
                                    messageSessionId,
                                    voiceEnabled,
                                    thinkingMessage
                            );
                        } else {

                            String thinkingContent = response.getMessage().getReasoningContent();
                            String fullReasoningContent = response.getMessage().getFullReasoningContent();
                            String content = response.getMessage().getContent();

                            if (StringUtils.hasLength(thinkingContent)) {
                                if (thinkingIdMap.get("id") == null) {
                                    thinkingIdMap.put("id", IdUtil.getSnowflake(1, 1).nextId());
                                }
                                thinkingIdMap.put("chainTitle", "🧠 思考");
                                thinkingIdMap.put("type", BotMessageTypeEnum.NORMAL.getValue());

                                thinkingMessage.setContent(thinkingContent);
                                thinkingMessage.setFullContent(fullReasoningContent);
                                logger.info("fullReasongingContent:{}", fullReasoningContent);
                                thinkingMessage.setMetadataMap(thinkingIdMap);

                                try {
                                    emitter.send(SseEmitter.event().name("thinking").data(JSON.toJSONString(thinkingMessage)));
                                } catch (IOException e) {
                                    throw new BusinessException("发送思考事件报错");
                                }
                            }


                            if (StringUtil.hasLength(content)) {


                                AiMessage message = response.getMessage();

                                // 检查是否已设置 messageSessionId，如果没有则设置
                                Map<String, Object> metadataMap = message.getMetadataMap();
                                if (metadataMap == null) {
                                    metadataMap = new HashMap<>();
                                    message.setMetadataMap(metadataMap);
                                }

                                // 确保 messageSessionId 存在
                                if (!metadataMap.containsKey("messageSessionId")) {
                                    metadataMap.put("messageSessionId", messageSessionId);
                                }

                                emitter.send(JSON.toJSONString(message));

                            }
                        }
                    }

                } catch (Exception e) {
                    logger.error("大模型调用出错：", e);
                    emitter.send(JSON.toJSONString(Maps.of("content", "大模型调用出错，请检查配置")));
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onStop(ChatContext context) {
                logger.info("normal chat complete");
                if (needClose[0]) {
                    emitter.complete();
                }
            }

            @Override
            public void onFailure(ChatContext context, Throwable throwable) {
                logger.error("大模型调用出错：", throwable);
                AiMessage aiMessage = new AiMessage();
                aiMessage.setContent("大模型调用出错，请检查配置");
                boolean hasUnsupportedApiError = containsUnsupportedApiError(throwable.getMessage());
                if (hasUnsupportedApiError) {
                    String errMessage = throwable.getMessage()
                            + "\n**以下是 AIFlowy 提供的可查找当前错误的方向**\n**1: 在 AIFlowy 中，Bot 对话需要大模型携带 function_calling 功能**" +
                            "\n**2: 请查看当前模型是否支持 function_calling 调用？**";
                    aiMessage.setContent(errMessage);
                }
                emitter.send(JSON.toJSONString(aiMessage));
                emitter.completeWithError(throwable);
            }

        }, chatOptions);

        return emitter;
    }

    @PostMapping("updateLlmId")
    @SaCheckPermission("/api/v1/aiBot/save")
    public Result updateBotLlmId(@RequestBody
                                 AiBot aiBot) {
        service.updateBotLlmId(aiBot);
        return Result.success();
    }


    private AiBotExternalMsgJsonResult handleMessageResult(AiMessage aiMessage) {
        AiBotExternalMsgJsonResult messageResult = new AiBotExternalMsgJsonResult();
        messageResult.setCreated(new Date().getTime());
        AiBotExternalMsgJsonResult.Usage usage = new AiBotExternalMsgJsonResult.Usage();
        if (aiMessage.getTotalTokens() != null) {
            usage.setTotalTokens(aiMessage.getTotalTokens());
        }
        if (aiMessage.getCompletionTokens() != null) {
            usage.setCompletionTokens(aiMessage.getCompletionTokens());
        }
        if (aiMessage.getPromptTokens() != null) {
            usage.setPromptTokens(aiMessage.getPromptTokens());
        }
        messageResult.setUsage(usage);
        AiBotExternalMsgJsonResult.Choice choice = new AiBotExternalMsgJsonResult.Choice();
        AiBotExternalMsgJsonResult.Message message = new AiBotExternalMsgJsonResult.Message();
        message.setContent(aiMessage.getContent());
        message.setRole("assistant");
        choice.setMessage(message);
        messageResult.setChoices(choice);
        messageResult.setStatus(aiMessage.getStatus().name());
        return messageResult;
    }

    // 辅助方法：创建响应
    private Object createResponse(boolean stream, String content) {
        if (stream) {
            MySseEmitter emitter = new MySseEmitter((long) (1000 * 60 * 2));
            emitter.send(content);
            emitter.complete();
            return emitter;
        } else {
            return ResponseEntity.ok(content);
        }
    }

    // 辅助方法：创建错误响应
    private Object createErrorResponse(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
    }

    /**
     * @param aiMessageResponse 大模型返回的消息
     * @param emitter
     * @param historiesPrompt   消息历史记录
     * @param llm               大模型
     */
    private String functionCall(
            AiMessageResponse aiMessageResponse,
            MySseEmitter emitter,
            HistoriesPrompt historiesPrompt,
            Llm llm,
            String sessionId,
            StringBuilder finalAnswerContentBuffer,
            ChatOptions chatOptions,
            String messageSessionId,
            boolean voiceEnabled,
            AiMessage thinkingMessage
    ) {
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(sra, true);

        String connectId = UUID.randomUUID().toString().replace("-", "");
        finalAnswerContentBuffer.setLength(0);

        AiMessage toolCallMessage = new AiMessage();
        toolCallMessage.setContent("\n\n\uD83D\uDCCB 调用工具中..." + "\n\n");
        toolCallMessage.setFullContent("\n\n\uD83D\uDCCB 调用工具中..." + "\n\n");
        toolCallMessage.setMetadataMap(Maps.of("showContent", toolCallMessage.getContent())
                .set("type", BotMessageTypeEnum.REACT_THINKING.getValue())
                .set("chainTitle", "\n\n\uD83D\uDCCB 调用工具" + "\n\n")
                .set("chainContent", "\n\n\uD83D\uDCCB 调用工具中..." + "\n\n")
                .set("id", IdUtil.getSnowflake(1, 1).nextId() + ""));

        try {
            emitter.send(SseEmitter.event().name("toolCalling").data(JSON.toJSONString(toolCallMessage)));
        } catch (IOException e) {
            throw new BusinessException("发送工具调用事件报错");
        }

        boolean[] alreadyAdd = {false};

        WebSocket finalWebSocket = null;

        llm.chatStream(ToolPrompt.of(aiMessageResponse), new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                RequestContextHolder.setRequestAttributes(sra, true);

                AiMessage message = response.getMessage();
                if (!alreadyAdd[0]) {

                    if (thinkingMessage != null && StringUtils.hasLength(thinkingMessage.getFullContent())) {
                        thinkingMessage.setFullContent("thinking:" + thinkingMessage.getFullContent());
                        historiesPrompt.addMessage(thinkingMessage);
                    }


                    historiesPrompt.addMessage(toolCallMessage);
                    alreadyAdd[0] = true;
                }


                // 检查是否已设置 messageSessionId，如果没有则设置
                Map<String, Object> metadataMap = message.getMetadataMap();
                if (metadataMap == null) {
                    metadataMap = new HashMap<>();
                    message.setMetadataMap(metadataMap);
                }

                // 确保 messageSessionId 存在
                if (!metadataMap.containsKey("messageSessionId")) {
                    metadataMap.put("messageSessionId", messageSessionId);
                }

                emitter.send(JSON.toJSONString(message));
            }

            @Override
            public void onStop(ChatContext context) {
                AiMessage lastAiMessage = context.getLastAiMessage();
                if (lastAiMessage != null) {
                    historiesPrompt.addMessage(lastAiMessage);
                }
                emitter.complete();
            }

            @Override
            public void onFailure(ChatContext context, Throwable throwable) {
                logger.error("function_call报错:", throwable);
                AiMessage aiMessage = new AiMessage();
                aiMessage.setContent("未查询到相关信息...");
                emitter.send(JSON.toJSONString(aiMessage));
            }
        }, chatOptions);

        return JSON.toJSONString("");
    }

    @GetMapping("getDetail")
    @SaIgnore
    public Result getDetail(String id) {
        return aiBotService.getDetail(id);
    }

    @Override
    @SaIgnore
    public Result detail(String id) {
        Result detail = aiBotService.getDetail(id);
        AiBot data = detail.get("data");

        if (data == null) {
            return detail;
        }

        Map<String, Object> llmOptions = data.getLlmOptions();
        if (llmOptions == null) {
            llmOptions = new HashMap<>();
        }

        if (data.getLlmId() == null) {
            return detail;
        }

        BigInteger llmId = data.getLlmId();
        AiLlm llm = aiLlmService.getById(llmId);

        if (llm == null) {
            data.setLlmId(null);
            return detail;
        }

        Map<String, Object> options = llm.getOptions();

        if (options != null && !options.isEmpty()) {

            // 获取是否多模态
            Boolean multimodal = (Boolean) options.get("multimodal");
            llmOptions.put("multimodal", multimodal != null && multimodal);

        }

        return detail;
    }

    @Override
    protected Result onSaveOrUpdateBefore(AiBot entity, boolean isSave) {

        String alias = entity.getAlias();

        if (StringUtils.hasLength(alias)) {
            AiBot aiBot = service.getByAlias(alias);


            if (aiBot != null && isSave) {
                throw new BusinessException("别名已存在！");
            }

            if (aiBot != null && aiBot.getId().compareTo(entity.getId()) != 0) {
                throw new BusinessException("别名已存在！");
            }

        }


        if (isSave) {
            // 设置默认值
            entity.setLlmOptions(getDefaultLlmOptions());
        }
        return super.onSaveOrUpdateBefore(entity, isSave);
    }

    private ChatOptions getChatOptions(Map<String, Object> llmOptions) {
        ChatOptions defaultOptions = new ChatOptions();
        if (llmOptions != null) {
            Object topK = llmOptions.get("topK");
            Object maxReplyLength = llmOptions.get("maxReplyLength");
            Object temperature = llmOptions.get("temperature");
            Object topP = llmOptions.get("topP");
            if (topK != null) {
                defaultOptions.setTopK(Integer.parseInt(String.valueOf(topK)));
            }
            if (maxReplyLength != null) {
                defaultOptions.setMaxTokens(Integer.parseInt(String.valueOf(maxReplyLength)));
            }
            if (temperature != null) {
                defaultOptions.setTemperature(Float.parseFloat(String.valueOf(temperature)));
            }
            if (topP != null) {
                defaultOptions.setTopP(Float.parseFloat(String.valueOf(topP)));
            }
        }
        return defaultOptions;
    }

    private Map<String, Object> getDefaultLlmOptions() {
        Map<String, Object> defaultLlmOptions = new HashMap<>();
        defaultLlmOptions.put("temperature", 0.7);
        defaultLlmOptions.put("topK", 4);
        defaultLlmOptions.put("maxReplyLength", 2048);
        defaultLlmOptions.put("topP", 0.7);
        defaultLlmOptions.put("maxMessageCount", 10);
        return defaultLlmOptions;
    }

    private Map<String, Object> errorRespnseMsg(int errorCode, String message) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("error", errorCode);
        result.put("message", message);
        return result;
    }

    private AiBotExternalMsgJsonResult handleMessageStreamJsonResult(AiMessage message) {
        AiBotExternalMsgJsonResult result = new AiBotExternalMsgJsonResult();
        AiBotExternalMsgJsonResult.Choice choice = new AiBotExternalMsgJsonResult.Choice();
        AiBotExternalMsgJsonResult.Delta delta = new AiBotExternalMsgJsonResult.Delta();
        delta.setRole("assistant");
        delta.setContent(message.getContent());
        choice.setDelta(delta);
        result.setCreated(new Date().getTime());
        result.setChoices(choice);
        result.setStatus(message.getStatus().name());

        return result;
    }

    private AiMessageResponse jsonResultJsonFunctionCall(AiMessageResponse aiMessageResponse,
                                                         HistoriesPrompt historiesPrompt, Llm llm, String prompt, ChatOptions chatOptions) {
        List<FunctionCaller> functionCallers = aiMessageResponse.getFunctionCallers();
        if (CollectionUtil.hasItems(functionCallers)) {
            for (FunctionCaller functionCaller : functionCallers) {
                Object result = functionCaller.call();
                if (ObjectUtil.isNotEmpty(result)) {
                    String newPrompt = "请根据以下内容回答用户，内容是:\n" + result + "\n 用户的问题是：" + prompt;
                    historiesPrompt.addMessageTemporary(new HumanMessage(newPrompt));
                    return llm.chat(historiesPrompt, chatOptions);
                }
            }
        }
        return aiMessageResponse;
    }

    private List<Function> buildFunctionList(Map<String, Object> buildParams) {

        if (buildParams == null || buildParams.isEmpty()) {
            throw new IllegalArgumentException("buildParams is empty");
        }

        List<Function> functionList = new ArrayList<>();

        BigInteger botId = (BigInteger) buildParams.get("botId");
        if (botId == null) {
            throw new IllegalArgumentException("botId is empty");
        }
        Boolean needEnglishName = (Boolean) buildParams.get("needEnglishName");
        if (needEnglishName == null) {
            needEnglishName = false;
        }

        QueryWrapper queryWrapper = QueryWrapper.create();

        // 工作流 function 集合
        queryWrapper.eq(AiBotWorkflow::getBotId, botId);
        List<AiBotWorkflow> aiBotWorkflows = aiBotWorkflowService.getMapper()
                .selectListWithRelationsByQuery(queryWrapper);
        if (aiBotWorkflows != null && !aiBotWorkflows.isEmpty()) {
            for (AiBotWorkflow aiBotWorkflow : aiBotWorkflows) {
                Function function = aiBotWorkflow.getWorkflow().toFunction(needEnglishName);
                functionList.add(function);
            }
        }

        // 知识库 function 集合
        queryWrapper = QueryWrapper.create();
        queryWrapper.eq(AiBotKnowledge::getBotId, botId);
        List<AiBotKnowledge> aiBotKnowledges = aiBotKnowledgeService.getMapper()
                .selectListWithRelationsByQuery(queryWrapper);
        if (aiBotKnowledges != null && !aiBotKnowledges.isEmpty()) {
            for (AiBotKnowledge aiBotKnowledge : aiBotKnowledges) {
                Function function = aiBotKnowledge.getKnowledge().toFunction(needEnglishName);
                functionList.add(function);
            }
        }

        // 插件 function 集合
        queryWrapper = QueryWrapper.create();
        queryWrapper.select("plugin_tool_id").eq(AiBotPlugins::getBotId, botId);
        List<BigInteger> pluginToolIds = aiBotPluginsService.getMapper()
                .selectListWithRelationsByQueryAs(queryWrapper, BigInteger.class);
        if (pluginToolIds != null && !pluginToolIds.isEmpty()) {
            QueryWrapper queryTool = QueryWrapper.create()
                    .select("*")
                    .from("tb_ai_plugin_tool")
                    .in("id", pluginToolIds);
            List<AiPluginTool> aiPluginTools = aiPluginToolService.getMapper().selectListWithRelationsByQuery(queryTool);
            if (aiPluginTools != null && !aiPluginTools.isEmpty()) {
                for (AiPluginTool aiPluginTool : aiPluginTools) {
                    functionList.add(aiPluginTool.toFunction());
                }
            }
        }


        return functionList;
    }


    private boolean containsUnsupportedApiError(String message) {
        if (message == null) {
            return false;
        }
        // 检查是否包含"暂不支持该接口"或其他相关关键词
        return message.contains("暂不支持该接口") || message.contains("不支持接口") || message.contains("接口不支持") || message
                .contains("The tool call is not supported");
    }

    @Override
    protected Result onRemoveBefore(Collection<Serializable> ids) {
        QueryWrapper queryWrapperKnowledge = QueryWrapper.create().in(AiBotKnowledge::getBotId, ids);
        aiBotKnowledgeService.remove(queryWrapperKnowledge);
        QueryWrapper queryWrapperBotWorkflow = QueryWrapper.create().in(AiBotWorkflow::getBotId, ids);
        aiBotWorkflowService.remove(queryWrapperBotWorkflow);
        QueryWrapper queryWrapperBotPlugins = QueryWrapper.create().in(AiBotPlugins::getBotId, ids);
        aiBotPluginsService.remove(queryWrapperBotPlugins);
        return super.onRemoveBefore(ids);
    }

}
