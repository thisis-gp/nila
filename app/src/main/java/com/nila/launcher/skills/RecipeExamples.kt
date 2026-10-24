package com.nila.launcher.skills

/** One hand-written recipe per template. The nearest one is shown to the model, and it is the fallback when the model fails. */
object RecipeExamples {
    private const val MONEY = """{"recipe":"nila.app/1","id":"expenses","title":"Expenses","version":"1.0.0",
"permissions":["storage.local","transactions.read"],
"data":{"txns":{"provider":"transactions"},"rules":{"provider":"rules","store":"expenses-rules"}},
"rules":[{"when":{"op":"contains","field":"merchant","value":"SWIGGY"},"set":{"category":"Food"}}],
"components":[
{"id":"root","component":"Column","children":["total","chart","list"]},
{"id":"total","component":"StatTile","label":"Spent this month","value":{"fn":"sum","of":"/txns","field":"amount","period":"month"}},
{"id":"chart","component":"Chart","kind":"donut","data":"/txns","groupBy":"category","period":"month"},
{"id":"list","component":"List","items":"/txns","template":"row","empty":"No payments yet"},
{"id":"row","component":"TxnRow","onCategoryChange":{"action":"rules.learn"}}]}"""

    private const val LOG = """{"recipe":"nila.app/1","id":"reading-log","title":"Reading Log","version":"1.0.0",
"permissions":["storage.local"],
"data":{"items":{"provider":"storage","store":"reading-log-storage"}},
"components":[
{"id":"root","component":"Column","children":["count","form","list"]},
{"id":"count","component":"StatTile","label":"Books this year","value":{"fn":"count","of":"/items","period":"year"}},
{"id":"form","component":"Form","fields":["name","pages"],"submit":{"action":"storage.add"},"submitLabel":"Add book"},
{"id":"name","component":"Input","label":"Book","field":"name","type":"text"},
{"id":"pages","component":"Input","label":"Pages","field":"pages","type":"number"},
{"id":"list","component":"List","items":"/items","template":"row","empty":"No books yet"},
{"id":"row","component":"ItemRow","titleField":"name","subtitleField":"pages","onTap":{"action":"storage.update"}}]}"""

    private const val COUNTER = """{"recipe":"nila.app/1","id":"water","title":"Water","version":"1.0.0",
"permissions":["storage.local"],
"data":{"items":{"provider":"storage","store":"water-storage"}},
"components":[
{"id":"root","component":"Column","children":["counter","chart"]},
{"id":"counter","component":"Counter","label":"Glasses today","value":{"fn":"count","of":"/items","period":"day"},"onStep":{"action":"counter.step"}},
{"id":"chart","component":"Chart","kind":"bar","data":"/items","period":"week"}]}"""

    private const val CHECKLIST = """{"recipe":"nila.app/1","id":"packing","title":"Packing","version":"1.0.0",
"permissions":["storage.local"],
"data":{"items":{"provider":"storage","store":"packing-storage"}},
"components":[
{"id":"root","component":"Column","children":["form","list"]},
{"id":"form","component":"Form","fields":["thing"],"submit":{"action":"storage.add"},"submitLabel":"Add"},
{"id":"thing","component":"Input","label":"Item","field":"name","type":"text"},
{"id":"list","component":"Checklist","items":"/items","labelField":"name","onToggle":{"action":"storage.update"}}]}"""

    val all: Map<String, String> = mapOf("money" to MONEY, "log" to LOG, "counter" to COUNTER, "checklist" to CHECKLIST)

    fun forTemplate(template: String): String = all.getValue(template)
}
