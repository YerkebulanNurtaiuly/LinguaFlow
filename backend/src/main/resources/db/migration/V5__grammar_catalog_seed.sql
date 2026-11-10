-- GRM-03: the catalog of common German grammar topics, per CEFR level, that learners can
-- pick from instead of typing. Read-only for the app; extend it with a new migration.
--
-- IDs are derived from the slug (md5 of 'grammar-catalog:<lang>:<slug>' read as a uuid),
-- so every environment has the same IDs and grammar_topics.catalog_topic_id stays stable.
-- Titles are German, descriptions English (the only explanation language so far).
-- Titles must stay distinct after normalization (case, ß/ss, a leading article), or a
-- learner couldn't add both; GrammarCatalogApiTests checks this.

insert into grammar_topic_catalog (id, language_code, slug, title, description, cefr_level)
select md5('grammar-catalog:de:' || slug)::uuid, 'de', slug, title, description, cefr_level
from (values
    -- A1
    ('praesens', 'Präsens', 'Present tense of regular verbs, sein and haben.', 'A1'),
    ('artikel-und-genus', 'Artikel und Genus', 'Definite and indefinite articles: der, die, das and ein, eine.', 'A1'),
    ('personalpronomen', 'Personalpronomen', 'ich, du, er, sie, es, wir, ihr, sie and the formal Sie.', 'A1'),
    ('nominativ', 'Nominativ', 'The subject case: who or what does something.', 'A1'),
    ('akkusativ', 'Akkusativ', 'Direct objects; den and einen for masculine nouns.', 'A1'),
    ('plural-der-nomen', 'Plural der Nomen', 'Plural endings -e, -en, -er, -s and the umlaut.', 'A1'),
    ('negation', 'Negation mit nicht und kein', 'When to say nicht and when kein.', 'A1'),
    ('w-fragen', 'W-Fragen', 'Questions with wer, was, wo, wann, wie and warum.', 'A1'),
    ('ja-nein-fragen', 'Ja/Nein-Fragen', 'Yes/no questions with the verb first.', 'A1'),
    ('wortstellung-im-hauptsatz', 'Wortstellung im Hauptsatz', 'The conjugated verb in second position.', 'A1'),
    ('possessivartikel', 'Possessivartikel', 'mein, dein, sein, ihr, unser, euer and their endings.', 'A1'),
    ('modalverben', 'Modalverben', 'können, müssen, dürfen, sollen, wollen and möchten.', 'A1'),
    ('trennbare-verben', 'Trennbare Verben', 'Separable prefixes: ankommen, aufstehen, einkaufen.', 'A1'),
    ('imperativ', 'Imperativ', 'Requests and commands: Komm! Kommt! Kommen Sie!', 'A1'),
    ('zahlen-und-uhrzeit', 'Zahlen und Uhrzeit', 'Numbers, dates and telling the time.', 'A1'),
    ('temporale-praepositionen', 'Temporale Präpositionen', 'am, um, im, von ... bis for points and spans of time.', 'A1'),
    -- A2
    ('dativ', 'Dativ', 'Indirect objects and dative prepositions (mit, bei, nach, von, zu ...).', 'A2'),
    ('perfekt', 'Perfekt', 'Past tense with haben or sein and the Partizip II.', 'A2'),
    ('wechselpraepositionen', 'Wechselpräpositionen', 'in, an, auf ... with the accusative (where to?) or dative (where?).', 'A2'),
    ('reflexive-verben', 'Reflexive Verben', 'sich waschen, sich freuen: verbs with reflexive pronouns.', 'A2'),
    ('nebensaetze-mit-weil-und-dass', 'Nebensätze mit weil und dass', 'Subordinate clauses: the conjugated verb goes to the end.', 'A2'),
    ('komparativ-und-superlativ', 'Komparativ und Superlativ', 'größer, am größten; comparisons with als and wie.', 'A2'),
    ('praeteritum-von-sein-und-haben', 'Präteritum von sein und haben', 'war and hatte, the past forms used even in speech.', 'A2'),
    ('personalpronomen-im-dativ', 'Personalpronomen im Dativ', 'mir, dir, ihm, ihr, uns, euch, ihnen.', 'A2'),
    ('konjunktionen', 'Konjunktionen', 'und, aber, oder, denn and sondern, which keep the word order.', 'A2'),
    ('verben-mit-dativ', 'Verben mit Dativ', 'helfen, danken, gefallen, gehören and other dative verbs.', 'A2'),
    ('lokale-praepositionen', 'Lokale Präpositionen', 'zu, nach, aus, bei and von for places and directions.', 'A2'),
    ('indirekte-fragen', 'Indirekte Fragen', 'Weißt du, wo ...? and ob in indirect questions.', 'A2'),
    -- B1
    ('praeteritum', 'Präteritum', 'Simple past, mainly in written German and for sein, haben and modal verbs.', 'B1'),
    ('adjektivdeklination', 'Adjektivdeklination', 'Adjective endings after der-words, ein-words and without an article.', 'B1'),
    ('relativsaetze', 'Relativsätze', 'der, die, das and their case forms as relative pronouns.', 'B1'),
    ('passiv-praesens', 'Passiv im Präsens', 'werden and the Partizip II: Das Haus wird gebaut.', 'B1'),
    ('konjunktiv-ii', 'Konjunktiv II', 'würde, hätte, wäre: wishes, polite requests and unreal situations.', 'B1'),
    ('genitiv', 'Genitiv', 'des and der, and genitive prepositions such as wegen, trotz, während.', 'B1'),
    ('infinitiv-mit-zu', 'Infinitiv mit zu', 'Ich habe keine Lust, zu arbeiten; um ... zu, ohne ... zu.', 'B1'),
    ('temporale-nebensaetze', 'Temporale Nebensätze', 'wenn, als, während, bevor, nachdem and seitdem.', 'B1'),
    ('n-deklination', 'N-Deklination', 'der Student, den Studenten: weak masculine nouns.', 'B1'),
    ('verben-mit-praepositionen', 'Verben mit Präpositionen', 'warten auf, denken an; the question words worauf, woran.', 'B1'),
    ('plusquamperfekt', 'Plusquamperfekt', 'hatte or war and the Partizip II for the past before the past.', 'B1'),
    ('passiv-praeteritum-und-perfekt', 'Passiv im Präteritum und Perfekt', 'wurde gebaut, ist gebaut worden.', 'B1'),
    -- B2
    ('passiv-mit-modalverben', 'Passiv mit Modalverben', 'Das muss erledigt werden; das kann repariert werden.', 'B2'),
    ('konjunktiv-i', 'Konjunktiv I', 'Reported speech: Er sagt, er sei krank.', 'B2'),
    ('partizipien-als-adjektive', 'Partizipien als Adjektive', 'der lachende Junge, das gekaufte Auto.', 'B2'),
    ('futur-i-und-ii', 'Futur I und II', 'werden and the infinitive; guesses about the future and the past.', 'B2'),
    ('konzessive-nebensaetze', 'Konzessive Nebensätze', 'obwohl, trotzdem and obgleich for contrasts.', 'B2'),
    ('nomen-verb-verbindungen', 'Nomen-Verb-Verbindungen', 'eine Entscheidung treffen, in Frage kommen.', 'B2'),
    ('zweiteilige-konnektoren', 'Zweiteilige Konnektoren', 'sowohl ... als auch, weder ... noch, je ... desto.', 'B2'),
    ('finale-und-kausale-nebensaetze', 'Finale und kausale Nebensätze', 'damit, um ... zu, da and weil compared.', 'B2'),
    -- C1
    ('nominalisierung', 'Nominalisierung', 'Turning verbs and clauses into nouns for a formal style.', 'C1'),
    ('subjektive-modalverben', 'Subjektiver Gebrauch der Modalverben', 'Er soll reich sein; sie muss es gewusst haben.', 'C1'),
    ('erweiterte-partizipialattribute', 'Erweiterte Partizipialattribute', 'die von der Regierung beschlossene Reform.', 'C1'),
    ('modalpartikeln', 'Modalpartikeln', 'doch, ja, mal, eben and halt in spoken German.', 'C1')
) as catalog (slug, title, description, cefr_level)
on conflict (language_code, slug) do nothing;
