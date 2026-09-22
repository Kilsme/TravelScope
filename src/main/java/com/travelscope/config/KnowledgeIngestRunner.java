package com.travelscope.config;

import com.travelscope.service.EsBm25Client;
import com.travelscope.service.RagServiceImpl;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库种子数据注入器（开发/检测用，FR-S11 配套）
 * <p>
 * travelscope.rag.seed-enabled=true 时启动执行：内置攻略片段（杭州为主，每条含
 * POI 名称/坐标/开放时间/建议时长/推荐理由）→ DashScope embedding → 双写
 * pgvector document_chunks（经 SimpleKnowledge.addDocuments）+ ES（BM25 可检索）。
 * 幂等：doc_id 用固定前缀 seed-{n}，已存在（按 title 查 ES 命中）则跳过。
 * 真实文档上传（FR-A03 管理端）落地后，本 Runner 仅作数据通道参考。
 * </p>
 * <p>
 * suppress "removal"：AgentScope 2.0.0 起整个 rag.model（Document/DocumentMetadata）
 * 标记 @Deprecated(forRemoval)，但 2.0.3 中 SimpleKnowledge.addDocuments /
 * VDBStoreBase.add 的入参仍是这组类且无替代 API——升级 3.x 时需按新 RAG API 重写。
 * </p>
 */
@Component
@SuppressWarnings("removal")
public class KnowledgeIngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestRunner.class);

    private final AppProperties appProperties;
    private final RagServiceImpl ragService;
    private final EsBm25Client esBm25Client;

    public KnowledgeIngestRunner(AppProperties appProperties, RagServiceImpl ragService,
                                 EsBm25Client esBm25Client) {
        this.appProperties = appProperties;
        this.ragService = ragService;
        this.esBm25Client = esBm25Client;
    }

    /** 种子片段：杭州主要 POI 攻略（检测标准 1/2 的数据基础） + 少量其他热门城市 */
    private static final List<String> SEED_DOCS = List.of(
            "西湖是杭州最著名的景点，位于杭州市西湖区龙井路1号，坐标120.1495,30.2461。湖面面积约6.38平方公里，"
                    + "环湖步行约需半天。开放时间全天免费，苏堤春晓、断桥残雪、三潭印月是最经典的打卡点。"
                    + "建议游玩时长4-6小时，适合清晨或傍晚游览避开人流。推荐理由：杭州旅行的必去核心景点，免费且四季景色各异。",
            "灵隐寺位于杭州市西湖区法云弄1号，坐标120.0972,30.2409。始建于东晋，是杭州最古老的佛教寺院。"
                    + "开放时间07:00-18:00，门票30元（含飞来峰）。飞来峰石窟造像是全国重点文物保护单位。"
                    + "建议游玩时长2-3小时。推荐理由：千年古刹人文底蕴深厚，香火旺盛，与飞来峰联游性价比高。",
            "西溪国家湿地公园位于杭州市西湖区天目山路518号，坐标120.0605,30.2606。中国首个国家湿地公园，"
                    + "《非诚勿扰》取景地。开放时间08:00-17:30，门票80元，摇橹船另计。建议游玩时长3-4小时。"
                    + "推荐理由：城市中难得的湿地生态，坐摇橹船穿行芦苇荡体验独特，适合喜欢安静的游客。",
            "雷峰塔位于杭州市西湖区南山路15号，坐标120.1509,30.2213。因《白蛇传》传说闻名，"
                    + "现塔为2002年重建，可乘电梯登塔俯瞰西湖全景。开放时间08:00-19:00（旺季），门票40元。"
                    + "建议游玩时长1-1.5小时，日落时分登塔看「雷峰夕照」最佳。推荐理由：西湖地标，登塔视野绝佳。",
            "河坊街位于杭州市上城区河坊街，坐标120.1707,30.2425。清河坊历史街区，杭州老字号聚集地，"
                    + "胡庆余堂、方回春堂、王星记扇庄都在此处。开放时间全天免费（店铺约10:00-22:00）。"
                    + "建议游玩时长2小时，晚上更热闹。推荐理由：体验杭州传统市井文化与美食的最佳街区，"
                    + "定胜糕、葱包桧、龙须糖等小吃必尝。",
            "中国京杭大运河博物馆位于杭州市拱墅区运河文化广场1号，坐标120.1451,30.3179，免费开放。"
                    + "开放时间09:00-16:30（周一闭馆）。旁边的拱宸桥是京杭大运河南端标志，桥西历史文化街区"
                    + "值得漫步。建议游玩时长2小时。推荐理由：了解大运河千年漕运史，小众但内涵丰富。",
            "千岛湖位于杭州市淳安县，距市区约150公里，坐标119.0323,29.6047。1078个岛屿星罗棋布，"
                    + "中心湖区游船票130元起。开放时间08:00-17:00。建议游玩时长1-2天（含交通）。"
                    + "推荐理由：长三角顶级山水湖泊度假地，适合行程宽裕时安排，鱼头宴是当地特色。",
            "宋城位于杭州市之江路148号，坐标120.1236,30.1862。大型宋文化主题公园，"
                    + "《宋城千古情》演出是核心看点。开放时间10:00-21:00（演出场次以当日为准），"
                    + "门票+演出联票约300元。建议游玩时长4-5小时。推荐理由：演出震撼是杭州夜游首选，"
                    + "适合家庭游客与喜欢文化演出的旅行者。",
            "九溪烟树位于杭州市西湖区九溪路，坐标120.1204,30.2052，免费开放。溪流、茶园、森林"
                    + "交织的徒步线路，从九溪公交站到龙井村约5公里。开放时间全天。建议游玩时长3小时。"
                    + "推荐理由：杭州人私藏的避暑徒步路线，串联九溪十八涧与龙井茶园，秋日红枫最美。",
            "杭州太子湾公园位于杭州市南山路5-1号，坐标120.1416,30.2289，免费开放。"
                    + "每年3-4月郁金香与樱花季是全年最美时刻。开放时间06:00-18:00。"
                    + "建议游玩时长1.5小时。推荐理由：春季杭州赏花天花板，与苏堤步行串联顺路。",
            "杭州博物馆位于杭州市粮道山18号，坐标120.1679,30.2457，免费开放（需预约）。"
                    + "开放时间09:00-16:30（周一闭馆）。馆藏战国水晶杯等国宝级文物。"
                    + "建议游玩时长2小时。推荐理由：快速理解杭州两千年历史的最佳入口，位于吴山脚下"
                    + "可与河坊街串联。",
            "断桥残雪位于杭州市西湖区白堤东端，坐标120.1525,30.2604，免费开放。"
                    + "《白蛇传》中许仙与白娘子相会之地，冬季雪后桥面阴阳各半的「残雪」景观最著名。"
                    + "建议游玩时长30分钟（拍照打卡）。推荐理由：西湖十景之一，白堤散步起点，"
                    + "清晨人少时体验最佳。",
            "杭州特色美食攻略：西湖醋鱼（楼外楼总店最正宗，人均150元）、东坡肉（肥而不腻，"
                    + "配米饭最佳）、龙井虾仁（春季明前茶入菜）、片儿川（奎元馆百年面馆招牌）、"
                    + "葱包桧（河坊街头小吃10元）。知味观、外婆家是性价比之选。美食集中在河坊街、"
                    + "胜利河美食街、大兜路历史街区。",
            "杭州住宿攻略：西湖景区周边（湖滨/南山路）出行方便但价格高，旺季500元起；"
                    + "地铁1号线沿线（凤起路、武林广场）性价比高，200-400元可选亚朵、全季；"
                    + "西溪湿地附近适合度假型住宿。青旅床位60-100元（明堂、懒墅推荐）。"
                    + "建议住地铁沿线，杭州地铁覆盖主要景点。",
            "杭州交通攻略：萧山国际机场距市区30公里，机场快线25元到武林门；杭州东站高铁枢纽，"
                    + "市内地铁1/3/4/5号线覆盖主要景点，支付宝乘车码通用。西湖景区周末单双号限行，"
                    + "建议公共出行。共享单车环西湖骑行1-2小时是经典体验。运河水上巴士3元"
                    + "从武林门到拱宸桥值得一坐。",
            "杭州两日游经典行程安排：第一天上午西湖环湖（断桥→白堤→平湖秋月→苏堤），"
                    + "中午楼外楼午餐，下午灵隐寺+飞来峰，傍晚雷峰塔看日落，晚上河坊街晚餐。"
                    + "第二天上午西溪湿地，中午龙井村茶餐，下午九溪烟树徒步或宋城（家庭游），"
                    + "晚上运河夜游或钱江新城灯光秀。两天节奏适中，覆盖杭州精华。",
            "杭州最佳旅游季节：3-5月春季（苏堤春晓、太子湾花期）与9-11月秋季（满陇桂雨、"
                    + "平湖秋月）最佳。6-7月梅雨季潮湿，7-8月炎热但适合看荷花，冬季游客最少"
                    + "且可能遇断桥残雪。穿衣建议：春秋薄外套，夏季防晒必备，西湖边风大。",
            "杭州亲子游推荐：杭州动物园（虎跑路40元）、浙江自然博物院（免费，恐龙化石）、"
                    + "极地海洋公园（萧山，门票330元）、宋城（演出适合大童）。行程建议每天只排"
                    + "1-2个点，午后留午休。西湖游船（55元）与手划船孩子最喜欢。",
            "杭州博物馆与人文路线：浙江省博物馆（孤山馆区，免费）、中国美院象山校区"
                    + "（王澍设计建筑）、胡雪岩故居（元宝街，门票20元）、南宋御街遗址。"
                    + "适合雨天或文化爱好者，均可在半天内完成两处。",
            "西安兵马俑位于西安市临潼区，坐标109.2781,34.3842。世界第八大奇迹，"
                    + "开放时间08:30-18:00，门票120元。建议游玩时长3小时，务必请讲解员。"
                    + "推荐理由：中国历史旅游的必访之地。",
            "成都大熊猫繁育研究基地位于成都市成华区，坐标104.1435,30.7334。开放时间07:30-18:00，"
                    + "门票55元。建议早上开园即到（熊猫活跃期）。建议游玩时长3小时。"
                    + "推荐理由：近距离观察国宝，成都旅行首选。",
            "北京故宫博物院位于北京市东城区，坐标116.3972,39.9169。开放时间08:30-17:00"
                    + "（周一闭馆），旺季60元。建议游玩时长4-6小时，中轴线三大殿为精华。"
                    + "推荐理由：世界现存最大木结构宫殿建筑群。");

    @Override
    public void run(ApplicationArguments args) {
        if (!appProperties.getRag().isSeedEnabled()) {
            return;
        }
        log.info("种子知识库注入开始: {} 条片段（pgvector + ES 双写）", SEED_DOCS.size());
        long start = System.currentTimeMillis();

        // ES 路：幂等建索引 + 检查是否已灌过（按 doc_id 前缀查一条）
        esBm25Client.ensureIndex();
        boolean alreadySeeded = !esBm25Client.search("seed 杭州攻略", 1).isEmpty()
                && !esBm25Client.search("西湖", 1).isEmpty();
        if (alreadySeeded && ragService.isAvailable()) {
            log.info("种子数据已存在（ES 检索命中），跳过注入");
            return;
        }

        // ES 写入（BM25 可检索）
        List<EsBm25Client.SeedChunk> esChunks = new ArrayList<>();
        for (int i = 0; i < SEED_DOCS.size(); i++) {
            String docId = "seed-" + (i + 1);
            esChunks.add(new EsBm25Client.SeedChunk(docId, "1",
                    seedTitle(i), SEED_DOCS.get(i), "seed"));
        }
        esBm25Client.bulkIndex(esChunks);
        esBm25Client.refresh();

        // pgvector 写入（语义检索，经 SimpleKnowledge 生成 embedding）
        try {
            SimpleKnowledge knowledge = buildKnowledgeForIngest();
            if (knowledge != null) {
                List<Document> documents = new ArrayList<>();
                for (int i = 0; i < SEED_DOCS.size(); i++) {
                    documents.add(new Document(DocumentMetadata.builder()
                            .content(io.agentscope.core.message.TextBlock.builder()
                                    .text(SEED_DOCS.get(i)).build())
                            .docId("seed-" + (i + 1))
                            .chunkId("1")
                            .payload(Map.of("title", seedTitle(i), "source", "seed"))
                            .build()));
                }
                knowledge.addDocuments(documents).block(java.time.Duration.ofMinutes(5));
                log.info("pgvector 写入完成: {} 条", documents.size());
            }
        } catch (Exception e) {
            log.warn("pgvector 种子写入失败（ES 路已写入，BM25 可用）: {}", e.getMessage());
        }

        log.info("种子知识库注入完成: {} 条, 耗时 {}ms", SEED_DOCS.size(),
                System.currentTimeMillis() - start);
    }

    /** 与 RagServiceImpl 同配置构建一次性写入器（避免暴露内部 knowledge 字段） */
    private SimpleKnowledge buildKnowledgeForIngest() {
        try {
            AppProperties.DashScopeConfig ds = appProperties.getDashscope();
            AppProperties.AgentScopeConfig as = appProperties.getAgentscope();
            io.agentscope.core.rag.store.PgVectorStore store =
                    io.agentscope.core.rag.store.PgVectorStore.builder()
                            .jdbcUrl("jdbc:postgresql://localhost:5432/travelscope")
                            .username("root")
                            .password("root")
                            .tableName(as.getVectorStoreTable())
                            .dimensions(as.getEmbeddingDimensions())
                            .distanceType(io.agentscope.core.rag.store.PgVectorStore.DistanceType.COSINE)
                            .build();
            return SimpleKnowledge.builder()
                    .embeddingModel(io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding.builder()
                            .apiKey(ds.getApiKey())
                            .modelName(ds.getEmbeddingModel())
                            .dimensions(ds.getEmbeddingDimensions())
                            .build())
                    .embeddingStore(store)
                    .build();
        } catch (Exception e) {
            log.warn("种子写入器构建失败: {}", e.getMessage());
            return null;
        }
    }

    private static String seedTitle(int i) {
        String content = SEED_DOCS.get(i);
        int cut = Math.min(18, content.length());
        return content.substring(0, cut);
    }
}
