import hashlib
import json
from datetime import datetime, timedelta, timezone


APPS = ["LanternList", "CopperTask", "MossBoard", "OrbitQueue", "BirchPlan"]

THEMES = [
    {
        "key": "android-task-manager-undecided",
        "app": "LanternList",
        "alias": "Lantern",
        "project": "synthetic-android-task-manager-review",
        "en": ("Android task managers for a small team", "LanternList works offline after sync and has a readable widget; CSV export is absent, background battery drain measured 11% per hour in one synthetic run, and notifications were killed by Doze", "verify background delivery under Doze, conflicting edits on two phones, export-and-restore, and battery use on a clean install", "DISCUSSION PAUSED: no decision was made; no app was accepted or rejected; all four tests are deferred"),
        "ru": ("диспетчеры задач Android для небольшой команды", "LanternList работает офлайн после синхронизации и имеет читаемый виджет; экспорта CSV нет, в одном синтетическом запуске расход батареи фоном составил 11% в час, а Doze остановил уведомления", "проверить доставку в фоне при Doze, конфликты правок на двух телефонах, экспорт с восстановлением и расход на чистой установке", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: решение не принято; ни одно приложение не принято и не отклонено; все четыре теста отложены"),
        "status": "undecided",
        "query_en": "Which Android task managers were discussed for a small team, what were the pros and cons, and what remains untested?",
        "query_ru": "Какие диспетчеры задач Android обсуждали для небольшой команды, каковы плюсы и минусы и что не проверили?",
        "facts_en": ["11%", "Doze", "CSV export", "two phones", "no decision"],
        "facts_ru": ["11%", "Doze", "CSV", "двух телефонах", "решение не принято"],
    },
    {
        "key": "five-task-apps",
        "app": "LanternList, CopperTask, MossBoard, OrbitQueue, BirchPlan",
        "alias": "five-candidate comparison",
        "project": "synthetic-android-task-manager-review",
        "en": ("five Android task managers", "one source discussion explored LanternList (offline widget, no CSV), CopperTask (CSV export, offline behavior unknown), MossBoard (board view, Android client unverified), OrbitQueue (self-hosted, setup cost unknown), and BirchPlan (calendar import, sync conflict behavior unknown)", "none of the five was tested end to end; keep each candidate and its open question attached to the source chat", "DISCUSSION PAUSED: five candidates were explored but no recommendation or decision was made"),
        "ru": ("пять диспетчеров задач Android", "в одном обсуждении рассмотрели LanternList (офлайн-виджет, нет CSV), CopperTask (есть CSV, офлайн неизвестен), MossBoard (доска, Android-клиент не проверен), OrbitQueue (self-hosted, настройка неизвестна) и BirchPlan (импорт календаря, конфликты синхронизации неизвестны)", "ни один из пяти не проверили полностью; сохрани кандидатов и открытый вопрос для каждого со ссылкой на чат", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: рассмотрели пять кандидатов, но рекомендацию или решение не приняли"),
        "status": "undecided",
        "query_en": "Which five Android task-management apps were explored in an earlier chat, and what remained unknown for each?",
        "query_ru": "Какие пять приложений задач Android рассматривали раньше и что осталось неизвестным по каждому?",
        "facts_en": ["LanternList", "CopperTask", "MossBoard", "OrbitQueue", "BirchPlan"],
        "facts_ru": ["LanternList", "CopperTask", "MossBoard", "OrbitQueue", "BirchPlan"],
    },
    {
        "key": "time-tracking",
        "app": "MintTrace",
        "alias": "Mint",
        "project": "lab-timesheets",
        "en": ("time tracking", "MintTrace batches offline timers and has CSV export, while background-service battery use and rounding rules remain unmeasured", "measure battery use during a six-hour idle run and compare exported totals", "DISCUSSION PAUSED: no decision was made; battery and rounding tests are deferred"),
        "ru": ("учёт времени", "MintTrace объединяет офлайн-таймеры и экспортирует CSV, но расход батареи фоновой службой и округление не измерялись", "измерить расход за шесть часов простоя и сверить суммы экспорта", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: решение не принято; тесты батареи и округления отложены"),
        "status": "undecided",
        "query_en": "What was learned about offline time tracking and what was deferred?",
        "query_ru": "Что выяснили об офлайн-учёте времени и что отложили?",
        "facts_en": ["offline", "CSV", "battery"],
        "facts_ru": ["офлайн", "CSV", "батар"],
    },
    {
        "key": "inventory-scan",
        "app": "MossBoard",
        "alias": "Moss",
        "project": "orchard-inventory",
        "en": ("inventory scanning", "MossBoard was mentioned as a possible barcode board, but no installation, feature review, or comparison has happened", "first confirm whether its Android client exists", "MENTIONED ONLY: MossBoard was not evaluated and no recommendation is supported"),
        "ru": ("учёт запасов", "MossBoard упомянули как возможную доску штрихкодов, но установку, проверку функций и сравнение не проводили", "сначала проверить, существует ли Android-клиент", "ТОЛЬКО УПОМЯНУТ: MossBoard не оценивали, рекомендации нет"),
        "status": "mentioned_not_evaluated",
        "query_en": "Was the barcode inventory app actually evaluated, or only mentioned?",
        "query_ru": "Приложение для штрихкодов действительно оценивали или только упоминали?",
        "facts_en": ["mentioned", "not evaluated", "barcode"],
        "facts_ru": ["упомян", "не оцен", "штрихкод"],
    },
    {
        "key": "repo-orbit",
        "app": "OrbitQueue",
        "alias": "Orbit",
        "project": "orbitqueue-android",
        "en": ("repository review", "OrbitQueue is the Android queue app in this repository; Orbit Systems is a separate vendor name and must not be merged as an entity", "check whether the repository URL belongs to the app before comparing features", "DISCUSSION PAUSED: no decision; alias resolution remains open"),
        "ru": ("проверка репозитория", "OrbitQueue — Android-приложение очереди в этом репозитории; Orbit Systems — отдельное имя поставщика, сущности нельзя объединять", "проверить принадлежность URL репозитория приложению до сравнения функций", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: решения нет; разрешение псевдонима открыто"),
        "status": "undecided",
        "query_en": "What did we say about Orbit and how do we distinguish the app from the vendor?",
        "query_ru": "Что обсуждали про Orbit и как отличить приложение от поставщика?",
        "facts_en": ["OrbitQueue", "Orbit Systems", "separate"],
        "facts_ru": ["OrbitQueue", "Orbit Systems", "отдель"],
        "repo_url": "https://github.com/synthetic-fixture/orbitqueue-android",
    },
    {
        "key": "cross-project-kite",
        "app": "KiteFlow",
        "alias": "Kite",
        "project": "mobile-release-tools",
        "en": ("release automation", "KiteFlow is a CI release tool in mobile-release-tools; PaperKite is a notes app in field-notes, and the shared Kite alias is not proof of identity", "keep project scope on every retrieved fact", "DISCUSSION PAUSED: no decision; cross-project identity must remain separate"),
        "ru": ("автоматизация релизов", "KiteFlow — CI-инструмент в mobile-release-tools; PaperKite — заметки в field-notes, общий псевдоним Kite не доказывает тождество", "сохранять проектную область у каждого найденного факта", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: решения нет; сущности разных проектов нужно разделять"),
        "status": "undecided",
        "query_en": "Compare the two things called Kite; keep project provenance clear.",
        "query_ru": "Сравни два объекта с именем Kite и сохрани происхождение по проектам.",
        "facts_en": ["KiteFlow", "PaperKite", "mobile-release-tools", "field-notes"],
        "facts_ru": ["KiteFlow", "PaperKite", "mobile-release-tools", "field-notes"],
        "repo_url": "https://github.com/synthetic-fixture/kiteflow",
    },
    {
        "key": "assistant-hypothesis",
        "app": "NorthVault",
        "alias": "Vault",
        "project": "archive-client",
        "en": ("backup client", "the assistant guessed NorthVault might encrypt its local archive, but the user explicitly rejected that as unverified; the documentation has not been checked", "do not convert an assistant hypothesis into a user decision", "DISCUSSION PAUSED: encryption status is unknown and no tool was accepted"),
        "ru": ("резервный клиент", "ассистент предположил шифрование архива NorthVault, но пользователь отметил, что это не проверено; документацию не смотрели", "не превращать гипотезу ассистента в решение пользователя", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: шифрование неизвестно, инструмент не выбран"),
        "status": "undecided",
        "query_en": "Was local encryption verified for the backup client, or was that an assistant guess?",
        "query_ru": "Локальное шифрование резервного клиента проверили или это была догадка ассистента?",
        "facts_en": ["unverified", "hypothesis", "unknown"],
        "facts_ru": ["не провер", "предполож", "неизвест"],
    },
    {
        "key": "missing-retention",
        "app": "QuietGrid",
        "alias": "Quiet",
        "project": "reader-pilot",
        "en": ("reader retention", "the offline reader's default retention period was not provided by any source in the discussion", "retrieve an official policy before answering; absence is not a zero-day default", "DISCUSSION PAUSED: retention remains unknown; do not infer a value"),
        "ru": ("срок хранения читалки", "в обсуждении не было источника со сроком хранения по умолчанию для офлайн-читалки", "найти официальную политику; отсутствие данных не означает ноль дней", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: срок неизвестен; значение нельзя додумывать"),
        "status": "unknown",
        "query_en": "What retention period did the reader have by default?",
        "query_ru": "Какой срок хранения был у читалки по умолчанию?",
        "facts_en": ["unknown", "not provided", "retention"],
        "facts_ru": ["неизвест", "не было", "хранен"],
    },
    {
        "key": "new-fact",
        "app": "BlueTrail",
        "alias": "Trail",
        "project": "field-maps",
        "en": ("offline maps", "an initial chat left tile size unknown; a later synthetic follow-up measured 18 MB for the 2 km test tile and did not change the overall no-decision status", "keep the later measurement timestamped and separate from the earlier unknown", "DISCUSSION PAUSED: tile size is now 18 MB for that fixture; route battery test remains deferred"),
        "ru": ("офлайн-карты", "в первом чате размер тайла был неизвестен; поздний синтетический тест измерил 18 МБ для тайла 2 км и не изменил статус решения", "сохранить позднее измерение с датой отдельно от прежнего неизвестного", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: тайл теста 18 МБ; проверка батареи на маршруте отложена"),
        "status": "undecided_with_new_fact",
        "query_en": "What later measurement was added for the offline map, and what test remains?",
        "query_ru": "Какое позднее измерение добавили для офлайн-карты и какая проверка осталась?",
        "facts_en": ["18 MB", "2 km", "battery"],
        "facts_ru": ["18 МБ", "2 км", "батар"],
    },
    {
        "key": "burst-timestamps",
        "app": "EchoCedar",
        "alias": "Echo",
        "project": "voice-capture",
        "en": ("voice capture", "three messages arrived in one timestamp burst; source order is message ID order, and timestamps alone cannot determine chronology", "preserve source IDs and stable ordering for equal timestamps", "DISCUSSION PAUSED: no decision; chronology must use source order for the burst"),
        "ru": ("запись голоса", "три сообщения пришли с одинаковой временной меткой; порядок задаёт ID источника, одной даты для хронологии недостаточно", "сохранить ID и стабильный порядок при одинаковых метках", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: решения нет; для серии нужен порядок источника"),
        "status": "undecided",
        "query_en": "How should the three same-time voice-capture messages be ordered?",
        "query_ru": "В каком порядке расположить три сообщения записи голоса с одинаковым временем?",
        "facts_en": ["source order", "message ID", "timestamp"],
        "facts_ru": ["порядок источника", "ID", "врем"],
    },
    {
        "key": "superseded",
        "app": "BranchBreeze",
        "alias": "Breeze",
        "project": "release-bot",
        "en": ("release automation", "BranchBreeze was initially adopted after dry-run tests, then superseded when the project required signed offline artifacts which it does not support", "report the current superseded state and preserve the earlier adoption chronology", "CURRENT STATUS: superseded; the former adoption is historical, not current"),
        "ru": ("автоматизация релизов", "BranchBreeze сначала приняли после dry-run, затем заменили, когда понадобились подписанные офлайн-артефакты, которых он не поддерживает", "указать текущий статус superseded и сохранить прежнее принятие в хронологии", "ТЕКУЩИЙ СТАТУС: заменён; прежнее принятие — история, не текущее решение"),
        "status": "superseded",
        "query_en": "What is the current state of the release bot, including its previous adoption?",
        "query_ru": "Каков текущий статус бота релизов с учётом прежнего принятия?",
        "facts_en": ["adopted", "superseded", "offline"],
        "facts_ru": ["прин", "замен", "офлайн"],
    },
    {
        "key": "revisit-reject",
        "app": "CopperLedger",
        "alias": "Copper",
        "project": "expense-importer",
        "en": ("expense import", "CopperLedger was tested and rejected because duplicate CSV rows doubled totals; it was revisited after a patch, but the fix has not been retested", "retain rejected-then-revisited chronology without marking it adopted", "CURRENT STATUS: still rejected pending a regression test"),
        "ru": ("импорт расходов", "CopperLedger проверили и отклонили: дубли CSV удваивали суммы; после исправления вернулись к нему, но фикс ещё не тестировали", "сохранить хронологию отказа и возврата, не объявлять принятие", "ТЕКУЩИЙ СТАТУС: пока отклонён, ждёт регрессионный тест"),
        "status": "rejected_revisited_pending_test",
        "query_en": "Was the duplicate-row bug retested after the fix, and what is the status?",
        "query_ru": "Проверили ли повторно ошибку дублей после исправления и каков статус?",
        "facts_en": ["duplicate", "not retested", "rejected"],
        "facts_ru": ["дубл", "не тест", "отклон"],
    },
    {
        "key": "api-migration",
        "app": "TokenNest",
        "alias": "Nest",
        "project": "auth-migration",
        "en": ("API migration", "TokenNest's v2 endpoint returned 202 in a synthetic smoke test, but token refresh, rate limits, and rollback behavior were not tested", "keep response evidence separate from production readiness", "DISCUSSION PAUSED: no migration decision; refresh and rollback checks are deferred"),
        "ru": ("миграция API", "эндпоинт TokenNest v2 вернул 202 в синтетическом smoke-тесте, но обновление токена, лимиты и откат не проверяли", "отделить факт ответа от готовности production", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: решения о миграции нет; обновление и откат отложены"),
        "status": "undecided",
        "query_en": "What exactly passed for the API migration and which checks are outstanding?",
        "query_ru": "Что именно прошло в миграции API и какие проверки остались?",
        "facts_en": ["202", "refresh", "rollback"],
        "facts_ru": ["202", "обновлен", "откат"],
    },
    {
        "key": "test-runner",
        "app": "TestHarbor",
        "alias": "Harbor",
        "project": "android-test-lab",
        "en": ("test runner", "TestHarbor passed 12 synthetic unit cases, while emulator stability, free-tier limits, and container privileges remain unverified", "record the 12-case result but do not generalize it to full acceptance", "DISCUSSION PAUSED: no runner chosen; emulator and permission tests are deferred"),
        "ru": ("тестовый раннер", "TestHarbor прошёл 12 синтетических unit-кейсов, но стабильность эмулятора, бесплатные лимиты и права контейнера не проверены", "сохранить результат 12 кейсов, не распространять его на полную приёмку", "ОБСУЖДЕНИЕ ПРИОСТАНОВЛЕНО: раннер не выбран; эмулятор и права отложены"),
        "status": "undecided",
        "query_en": "How many unit cases passed and what prevents accepting the runner?",
        "query_ru": "Сколько unit-кейсов прошло и что мешает принять раннер?",
        "facts_en": ["12", "emulator", "permissions"],
        "facts_ru": ["12", "эмулятор", "права"],
    },
    {
        "key": "sync-reversal",
        "app": "TaskHarbor",
        "alias": "Harbor",
        "project": "crew-planner",
        "en": ("task sync", "TaskHarbor was considered, tested, rejected for stale offline writes, revisited after a sync patch, then adopted after a two-device test; it was later superseded by an export requirement", "preserve each dated state transition and the final superseded status", "CURRENT STATUS: superseded; adoption was real at the time but is no longer current"),
        "ru": ("синхронизация задач", "TaskHarbor рассмотрели, проверили, отклонили за устаревшие офлайн-записи, вернулись после фикса синхронизации и приняли после теста двух устройств; затем заменили из-за экспорта", "сохранить каждый датированный переход и финальный статус superseded", "ТЕКУЩИЙ СТАТУС: заменён; принятие было действительным тогда, но сейчас неактуально"),
        "status": "superseded_after_adoption",
        "query_en": "Reconstruct the task sync decision chronology and state the current verdict.",
        "query_ru": "Восстанови хронологию синхронизации задач и назови текущий статус.",
        "facts_en": ["rejected", "adopted", "superseded"],
        "facts_ru": ["отклонили", "приняли", "заменили"],
    },
]


def messages_for(case_id, lang, topic, title, positive, unknown, final_status, date, alias):
    if topic["key"] in {"superseded", "revisit-reject", "sync-reversal"}:
        if lang == "en":
            if topic["key"] == "superseded":
                rows = [
                    ("user", "We are evaluating BranchBreeze for the synthetic release-bot project. Nothing has been decided."),
                    ("assistant", "Recorded as considered only; I will not describe it as adopted yet."),
                    ("user", "Two days later: the dry-run passed the stated fixture, so the team adopted BranchBreeze for this scope."),
                    ("assistant", "Adoption is current as of that dated update; keep its limited dry-run scope."),
                    ("user", "A later requirement now demands signed offline artifacts. BranchBreeze cannot provide them."),
                    ("assistant", "That is new evidence against the earlier choice; the old adoption remains historical."),
                    ("user", "Current status: BranchBreeze is superseded. Do not report the old adoption as current."),
                    ("assistant", "I will preserve considered → adopted → superseded with the source timestamps; no other capability is implied."),
                ]
            elif topic["key"] == "revisit-reject":
                rows = [
                    ("user", "We are evaluating CopperLedger for the synthetic expense-importer project. Status is considered."),
                    ("assistant", "No test result or decision is recorded yet."),
                    ("user", "After the CSV fixture test, duplicate rows doubled totals. We rejected CopperLedger for this reason."),
                    ("assistant", "The rejection applies to the tested version and exact duplicate-row fixture."),
                    ("user", "A patch was later proposed and we revisited CopperLedger, but nobody reran the regression test."),
                    ("assistant", "A proposed patch does not establish that the defect is fixed."),
                    ("user", "Current status remains rejected pending that regression test. It is not adopted."),
                    ("assistant", "I will preserve considered → tested → rejected → revisited, with retest still open."),
                ]
            else:
                rows = [
                    ("user", "We are evaluating TaskHarbor for the synthetic crew-planner project. Status: considered."),
                    ("assistant", "No selection yet; preserve the project scope."),
                    ("user", "The first two-device test found stale offline writes, so we rejected TaskHarbor."),
                    ("assistant", "That rejection is tied to the tested sync behavior."),
                    ("user", "After a sync patch, we revisited it. A new two-device test passed, and the team adopted it."),
                    ("assistant", "Adoption is valid from this update, not before the passing test."),
                    ("user", "Later, export became mandatory and TaskHarbor did not support it. We superseded the earlier adoption."),
                    ("assistant", "Current status is superseded; retain considered → tested → rejected → revisited → adopted → superseded in order."),
                ]
        else:
            if topic["key"] == "superseded":
                rows = [
                    ("user", "В синтетическом проекте release-bot рассматриваем BranchBreeze. Решения пока нет."),
                    ("assistant", "Записываю только рассмотрение, не принятие."),
                    ("user", "Через два дня dry-run прошёл заданный тест, и команда приняла BranchBreeze для этой области."),
                    ("assistant", "Принятие действует на дату обновления и только в пределах dry-run."),
                    ("user", "Позже появилось новое требование: подписанные офлайн-артефакты. BranchBreeze этого не умеет."),
                    ("assistant", "Это новые данные против прежнего выбора; прежнее принятие остаётся историей."),
                    ("user", "Текущий статус: BranchBreeze заменён. Не называй прежнее принятие текущим."),
                    ("assistant", "Сохраню хронологию рассмотрен → принят → заменён с исходными метками времени; другие свойства не подтверждены."),
                ]
            elif topic["key"] == "revisit-reject":
                rows = [
                    ("user", "Рассматриваем CopperLedger для синтетического проекта expense-importer. Пока только рассмотрение."),
                    ("assistant", "Результатов теста и решения пока нет."),
                    ("user", "После теста CSV дубли строк удвоили суммы. CopperLedger отклонили по этой причине."),
                    ("assistant", "Отказ относится к проверенной версии и конкретному тесту дублей."),
                    ("user", "Позже предложили исправление и вернулись к CopperLedger, но регрессионный тест не запускали."),
                    ("assistant", "Предложенный фикс не доказывает устранение дефекта."),
                    ("user", "Текущий статус остаётся: отклонён до регрессионного теста. Приложение не принято."),
                    ("assistant", "Сохраню рассмотрение → тест → отказ → возврат; повторная проверка остаётся открытой."),
                ]
            else:
                rows = [
                    ("user", "Рассматриваем TaskHarbor для синтетического проекта crew-planner. Статус: рассмотрение."),
                    ("assistant", "Выбора пока нет; сохраняю область проекта."),
                    ("user", "Первый тест на двух устройствах выявил устаревшие офлайн-записи, поэтому TaskHarbor отклонили."),
                    ("assistant", "Отказ связан с проверенным поведением синхронизации."),
                    ("user", "После исправления синхронизации вернулись к нему. Новый тест на двух устройствах прошёл, команда приняла приложение."),
                    ("assistant", "Принятие действительно с этого обновления, а не до успешного теста."),
                    ("user", "Позже экспорт стал обязательным требованием, а TaskHarbor его не поддерживает. Прежнее принятие заменили."),
                    ("assistant", "Текущий статус — заменён; сохрани по порядку рассмотрение → тест → отказ → возврат → принятие → замена."),
                ]
    elif lang == "en":
        rows = [
            ("user", f"We are discussing {title} for synthetic project {topic['project']}. This is a fabricated evaluation transcript, not a real deployment. The product name is {topic['app']}, sometimes called {alias}; keep that alias attached to this project."),
            ("assistant", f"Initial hypothesis only: {topic['app']} may fit {title}. Nothing has been selected. We need source evidence and should keep assistant guesses separate from user-confirmed facts."),
            ("user", f"Observed in the synthetic review: {positive}. This is the favorable side, not an acceptance decision. The comparison must preserve the exact measurement and its scope."),
            ("assistant", f"Trade-off noted: the same discussion also says {unknown}. I have not verified anything beyond the supplied transcript, so I should cite this source instead of filling gaps."),
            ("user", "Open questions: which platform versions are covered, can the data be exported and restored, and does the behavior persist after restart? No answer is present yet."),
            ("assistant", "Proposed next checks are a cold-start test, an export-and-restore test, and a second-device test. These are proposals only; none has run in this conversation."),
            ("user", f"{final_status}. Keep the open questions and the favorable and unfavorable evidence. Do not silently convert this ending into approval or rejection."),
            ("assistant", "I will preserve the discussion as unresolved evidence with its source timestamps. A future lookup should say what was discussed, what remains unknown, and that no unsupported verdict exists."),
        ]
    else:
        rows = [
            ("user", f"Обсуждаем {title} для синтетического проекта {topic['project']}. Это вымышленная запись оценки, не реальное внедрение. Продукт называется {topic['app']}, иногда {alias}; сохраняй псевдоним в контексте проекта."),
            ("assistant", f"Только гипотеза: {topic['app']} может подойти для темы «{title}». Ничего не выбрано. Нужны источники; догадки ассистента отделяем от подтверждённых пользователем фактов."),
            ("user", f"Наблюдение из синтетической проверки: {positive}. Это плюсы, но не решение о принятии. В сравнении нужно сохранить точное измерение и его область."),
            ("assistant", f"Зафиксирован компромисс: в том же обсуждении сказано, что {unknown}. Я не проверял ничего сверх этой записи и не должен заполнять пробелы догадками."),
            ("user", "Открытые вопросы: какие версии платформы охвачены, можно ли экспортировать и восстановить данные, сохраняется ли поведение после перезапуска? Ответов в записи нет."),
            ("assistant", "Предложены проверки холодного старта, экспорта с восстановлением и второго устройства. Это только предложения: в этом разговоре ни одна проверка не запускалась."),
            ("user", f"{final_status}. Сохрани открытые вопросы, плюсы и минусы. Не превращай окончание разговора в принятие или отказ."),
            ("assistant", "Сохраню обсуждение как нерешённые данные с временными метками источника. При следующем поиске нужно назвать обсуждённое, неизвестное и отсутствие подтверждённого вердикта."),
        ]
    if topic["status"] in {"undecided", "mentioned_not_evaluated", "unknown", "undecided_with_new_fact"}:
        if lang == "en":
            rows[-1] = (rows[-1][0], rows[-1][1] + " Explicit status: no decision was made; do not infer approval or rejection.")
        else:
            rows[-1] = (rows[-1][0], rows[-1][1] + " Явный статус: решение не принято; нельзя выводить принятие или отказ.")
    start = datetime.fromisoformat(date).replace(tzinfo=timezone.utc)
    if topic["key"] in {"superseded", "revisit-reject", "sync-reversal"}:
        stamps = [start + timedelta(days=day) for day in (0, 0, 2, 2, 9, 9, 15, 15)]
    elif case_id in {"case-019-en", "case-020-ru"}:
        stamps = [start + timedelta(minutes=3) if i in {1, 2, 3} else start + timedelta(minutes=i) for i in range(len(rows))]
    else:
        stamps = [start + timedelta(minutes=i) for i in range(len(rows))]
    return [
        {"id": f"src-{case_id}-turn-{index:02d}", "role": role, "timestamp": stamps[index - 1].isoformat(timespec="milliseconds").replace("+00:00", "Z"), "content": content}
        for index, (role, content) in enumerate(rows, 1)
    ]


def build_cases():
    cases = []
    for index, topic in enumerate(THEMES, 1):
        for lang in ("en", "ru"):
            text = topic[lang]
            case_id = f"case-{(index - 1) * 2 + (1 if lang == 'en' else 2):03d}-{lang}"
            date = f"2026-{7 + (index - 1) // 6:02d}-{1 + ((index - 1) % 6) * 3:02d}T09:00:00"
            turns = messages_for(case_id, lang, topic, text[0], text[1], text[2], text[3], date, topic["alias"])
            raw = json.dumps(turns, ensure_ascii=False, separators=(",", ":"))
            cases.append({
                "case_id": case_id,
                "language": lang,
                "project": topic["project"],
                "product": topic["app"],
                "alias": topic["alias"],
                "status": topic["status"],
                "source_chat_id": f"source-{case_id}",
                "separate_query_chat_id": f"lookup-{case_id}",
                "query": topic[f"query_{lang}"],
                "required_facts": topic[f"facts_{lang}"],
                "messages": turns,
                "raw_transcript": raw,
                "raw_sha256": hashlib.sha256(raw.encode()).hexdigest(),
                "repo_url": topic.get("repo_url"),
            })
    return cases


def write_corpus(path):
    cases = build_cases()
    payload = {"schema_version": 1, "synthetic_only": True, "case_count": len(cases), "cases": cases}
    with open(path, "w", encoding="utf-8") as output:
        json.dump(payload, output, ensure_ascii=False, indent=2)
        output.write("\n")
    return payload


if __name__ == "__main__":
    import sys

    corpus = write_corpus(sys.argv[1])
    print(f"synthetic_cases={corpus['case_count']} messages={sum(len(c['messages']) for c in corpus['cases'])}")
