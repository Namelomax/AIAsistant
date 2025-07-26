package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.replaceHistoryWithTLDR
import ai.koog.agents.core.environment.AIAgentEnvironment
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.message.Message
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import io.github.cdimascio.dotenv.dotenv
import org.example.AppConfig.openRouterApiKey

@Serializable
data class DialogSession(
    val sessionId: String = generateSessionId(),
    val prompt: Prompt = Prompt.Empty,
    val messageCount: Int = 0
) {
    companion object {
        fun generateSessionId(): String = "session_${System.currentTimeMillis()}"
    }
}

@Serializable
data class CompanyData(
    val company: Company,
    val projects: List<Project>,
    val contacts: Contacts,
    val policies: Policies,
    val developmentStandards: DevelopmentStandards
)

@Serializable
data class Company(
    val name: String,
    val foundingYear: Int,
    val mission: String,
    val values: List<String>,
    val departments: List<Department>
)

@Serializable
data class Department(
    val name: String,
    val head: String,
    val teamSize: Int
)

@Serializable
data class Project(
    val name: String,
    val status: String,
    val description: String,
    val techStack: List<String>? = null,
    val team: Team? = null,
    val projectStructure: ProjectStructure? = null,
    val documentationLinks: Map<String, String>? = null,
    val onboardingTasks: List<String>? = null
)

@Serializable
data class Team(
    val backend: List<String>? = null,
    val frontend: List<String>? = null,
    val dataScience: List<String>? = null,
    val qa: List<String>? = null,
    val productOwner: String? = null
)

@Serializable
data class ProjectStructure(
    val components: List<Component>? = null,
    val styles: Styles? = null
)

@Serializable
data class Component(
    val name: String,
    val description: String,
    val tech: String,
    val graph: Graph? = null,
    val elements: List<Element>? = null
)

@Serializable
data class Graph(
    val roots: List<GraphRoot>
)

@Serializable
data class GraphRoot(
    val type: String,
    val children: List<GraphChild>? = null
)

@Serializable
data class GraphChild(
    val type: String,
    val parameters: Parameters
)

@Serializable
data class Parameters(
    val name: String? = null,
    val pages: Pages? = null
)

@Serializable
data class Pages(
    val routes: List<String>,
    val startPage: String
)

@Serializable
data class Element(
    val type: String,
    val parameters: ElementParameters
)

@Serializable
data class ElementParameters(
    val textStyle: String,
    val text: String
)

@Serializable
data class Styles(
    val textStyles: Map<String, TextStyle>,
    val colors: Map<String, String>
)

@Serializable
data class TextStyle(
    val fontSize: String,
    val fontFamily: String
)

@Serializable
data class Contacts(
    val hr: String,
    val itSupport: String,
    val officeAddress: String,
    val emergencyContact: String
)

@Serializable
data class Policies(
    val workSchedule: String,
    val remoteWork: String,
    val vacationPolicy: String,
    val probationPeriod: String
)

@Serializable
data class DevelopmentStandards(
    val gitFlow: GitFlow,
    val codeReview: CodeReview,
    val testing: Testing
)

@Serializable
data class GitFlow(
    val branchNaming: String,
    val commitMessage: String,
    val mergeRequests: String
)

@Serializable
data class CodeReview(
    val timeLimit: String,
    val checklist: List<String>
)

@Serializable
data class Testing(
    val unitTestCoverage: String,
    val e2eTests: String
)


class SessionManager(val filePath: String = "dialog_session.json") {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun saveSession(session: DialogSession) = try {
        File(filePath).writeText(json.encodeToString(session))
    } catch (e: Exception) {
        println("Ошибка сохранения сессии: ${e.message}")
    }

    fun loadSession(): DialogSession = try {
        val file = File(filePath)
        if (file.exists() && file.length() > 0) json.decodeFromString(file.readText())
        else DialogSession()
    } catch (e: Exception) {
        println("Ошибка загрузки сессии: ${e.message}")
        DialogSession()
    }
}

class DataLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun loadCompanyData(filePath: String = "company_data.json"): CompanyData {
        val inputStream: InputStream = File(filePath).inputStream()
        return json.decodeFromString(inputStream.reader().readText())
    }
}

object AppConfig {
    private val dotenv = dotenv {
        directory = "./"
        ignoreIfMissing = true
    }

    val openRouterApiKey: String = dotenv["OPENROUTER_API_KEY"]
        ?: throw IllegalStateException("OPENROUTER_API_KEY not found in .env")
}

fun main() = runBlocking {
    val companyData = try {
        DataLoader().loadCompanyData()
    } catch (e: Exception) {
        println("Ошибка загрузки данных компании: ${e.message}")
        return@runBlocking
    }

    val sessionManager = SessionManager()
    var currentSession = sessionManager.loadSession()

    val agentEnvironment = object : AIAgentEnvironment {
        override suspend fun executeTools(toolCalls: List<Message.Tool.Call>) = emptyList<ReceivedToolResult>()
        override suspend fun reportProblem(e: Throwable) = println("Ошибка: ${e.message}")
        override suspend fun sendTermination(result: String?) = println("Сессия завершена")
    }
    val systemPrompt = """
    Ты — ассистент для онбординга новых сотрудников в компании ${companyData.company.name}.
    Компания основана в ${companyData.company.foundingYear} году. 
    Миссия: ${companyData.company.mission}.
    
    Ценности компании: ${companyData.company.values.joinToString(", ")}.
    
    Отделы:
    ${companyData.company.departments.joinToString("\n") { dept ->
        "- ${dept.name}, руководитель: ${dept.head}, в команде ${dept.teamSize} человек"
    }}
    
    Основные проекты:
    ${companyData.projects.joinToString("\n\n") { project ->
        buildString {
            append("- ${project.name} (${project.status})\n")
            append("  Описание: ${project.description}\n")
            if (!project.techStack.isNullOrEmpty()) {
                append("  Технологии: ${project.techStack.joinToString(", ")}\n")
            }
            project.team?.let { team ->
                append("  Команда:\n")
                team.backend?.let { append("    Backend: ${it.joinToString(", ")}\n") }
                team.frontend?.let { append("    Frontend: ${it.joinToString(", ")}\n") }
                team.dataScience?.let { append("    Data Science: ${it.joinToString(", ")}\n") }
                team.qa?.let { append("    QA: ${it.joinToString(", ")}\n") }
                append("    Product Owner: ${team.productOwner}\n")
            }
            project.onboardingTasks?.let {
                append("  Задачи на онбординг:\n")
                it.forEach { task -> append("    - $task\n") }
            }
        }
    }}
    
    Политики компании:
    - Рабочий график: ${companyData.policies.workSchedule}
    - Удалённая работа: ${companyData.policies.remoteWork}
    - Отпуск: ${companyData.policies.vacationPolicy}
    - Испытательный срок: ${companyData.policies.probationPeriod}
    
    Стандарты разработки:
    Git Flow:
    - Именование веток: ${companyData.developmentStandards.gitFlow.branchNaming}
    - Коммиты: ${companyData.developmentStandards.gitFlow.commitMessage}
    - Merge Requests: ${companyData.developmentStandards.gitFlow.mergeRequests}
    
    Code Review:
    - Срок: ${companyData.developmentStandards.codeReview.timeLimit}
    - Чеклист: ${companyData.developmentStandards.codeReview.checklist.joinToString("; ")}
    
    Тестирование:
    - Покрытие unit-тестами: ${companyData.developmentStandards.testing.unitTestCoverage}
    - E2E-тесты: ${companyData.developmentStandards.testing.e2eTests}
    
    Контакты:
    - HR: ${companyData.contacts.hr}
    - IT-поддержка: ${companyData.contacts.itSupport}
    - Адрес офиса: ${companyData.contacts.officeAddress}
    - Экстренная связь: ${companyData.contacts.emergencyContact}
    
    Отвечай на вопросы, используя информацию о компании. Будь дружелюбным, понятным и профессиональным.
""".trimIndent()


    val agent = AIAgent(
        executor = simpleOpenRouterExecutor(openRouterApiKey),
        systemPrompt = systemPrompt,
        llmModel = OpenRouterModels.Claude3Haiku
    )
    val initialPrompt = if (currentSession.prompt == Prompt.Empty) {
        Prompt.build(id = "onboarding-prompt") {
            system(systemPrompt)
        }
    } else {
        currentSession.prompt
    }
    val llmContext = AIAgentLLMContext(
        tools = emptyList(),
        toolRegistry = ToolRegistry.EMPTY,
        prompt = initialPrompt,
        model = OpenRouterModels.Claude3Haiku,
        promptExecutor = simpleOpenRouterExecutor(openRouterApiKey),
        environment = agentEnvironment,
        config = agent.agentConfig,
        clock = Clock.System
    )



    println("\nДобро пожаловать в ассистент онбординга ${companyData.company.name}!")
    println("Задавайте вопросы о компании или проектах. Для выхода введите 'exit'.")

    while (true) {
        print("\nВы: ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.equals("exit", true)) break

        val response = llmContext.writeSession {
            updatePrompt { user(input) }
            val response = requestLLM()
            if (prompt.messages.size > 10) replaceHistoryWithTLDR(HistoryCompressionStrategy.Chunked(10))
            response
        }

        println("\nАссистент: ${response.content}")

        currentSession = llmContext.readSession {
            currentSession.copy(prompt = prompt, messageCount = prompt.messages.size)
        }
        sessionManager.saveSession(currentSession)
    }

    println("\nСессия завершена. Всего сообщений: ${currentSession.messageCount}")
}