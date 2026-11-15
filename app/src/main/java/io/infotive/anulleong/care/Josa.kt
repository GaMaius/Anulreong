package io.infotive.anulleong.care

/** Korean particle choice by final consonant (받침): josa("회전교차로", "이", "가") == "회전교차로가". */
fun josa(word: String, withBatchim: String, without: String): String {
    val c = word.trimEnd().lastOrNull() ?: return word + without
    val has = c in '가'..'힣' && (c - '가') % 28 != 0
    return word + if (has) withBatchim else without
}

/** "회전교차로예요" / "급커브이에요" -> 이에요 after a final consonant, 예요 otherwise. */
fun ieyo(word: String): String = josa(word, "이에요", "예요")
