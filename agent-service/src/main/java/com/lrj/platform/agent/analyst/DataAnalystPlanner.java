package com.lrj.platform.agent.analyst;

import com.lrj.platform.agent.dag.AgentDagPlan;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 数据分析智能体的 DAG 规划器：把一个自然语言数据问题拆成「探表→取数→计算→解读」子任务，
 * 交给现有 {@code AgentDagService} 引擎并行/依赖执行。
 *
 * <p>刻意独立成一个接口（<strong>不继承 {@code AgentDagPlanner}</strong>）——若继承，则会多出一个
 * {@code AgentDagPlanner} bean，与 {@code AgentDagService} 构造注入的通用 planner 产生歧义。
 */
public interface DataAnalystPlanner {

    @SystemMessage("""
            你为一个「数据分析多 Agent DAG」规划子任务。每个子任务会交给一个能调用以下只读数据工具的 worker：
            - schema_explore：探查库结构。actionInput 留空=列出所有可查表；填表名=查看该表字段、类型与枚举取值。
            - analytics_sql：用自然语言查业务库（只读、带安全护栏、自动按当前租户过滤），用于取数。
            - code_exec：对已取回的数字做精确二次计算（求和/占比/环比等）。可能默认关闭；不可用时 worker 会退化为推理估算。

            规划规则：
            - 产出 1 到 6 个子任务，id 用 t1, t2, t3, ...
            - 先探后取：通常第一个子任务先用 schema_explore 确认与问题相关的表结构与枚举取值；
              后续取数/计算子任务用 dependsOn 依赖它，这样 worker 才知道真实列名与中文枚举，不会凭空猜。
            - 每个子任务描述里明确「这一步用哪个工具、查什么/算什么」，写成对 worker 自足的中文指令。
            - 不要过度拆解：单一维度的问题只用一个取数子任务即可。多维度按维度拆，不要按实体拆。
            - 只在子任务确实需要上游结果时才加 dependsOn；相互独立的取数子任务留空 dependsOn 以便并行。
            - 图必须无环。用用户的语言（中文）。

            示例：
            目标：「上月退款金额 top5 的客户是谁，各退多少，占总退款额多少？」
            子任务：
            t1: 用 schema_explore 查看 refunds、customers 表的字段与状态枚举取值, dependsOn=[]
            t2: 基于 t1 的真实字段，用 analytics_sql 查询上月各客户退款总额并取前 5, dependsOn=["t1"]
            t3: 基于 t1 的真实字段，用 analytics_sql 查询上月退款总额合计, dependsOn=["t1"]
            t4: 用 code_exec 基于 t2、t3 的数字计算 top5 各自占总额的比例, dependsOn=["t2","t3"]
            """)
    @UserMessage("""
            用户数据分析目标：
            {{it}}
            """)
    AgentDagPlan plan(String goal);
}
