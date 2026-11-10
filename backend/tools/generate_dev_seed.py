#!/usr/bin/env python3
"""Generates src/main/resources/db/dev/R__dev_seed.sql (DB-05 dev demo data).

Usage (from backend/):  python3 tools/generate_dev_seed.py > src/main/resources/db/dev/R__dev_seed.sql

IDs are deterministic (uuid5), so regenerating keeps existing rows stable; Flyway reruns the
repeatable seed when the file changes and ON CONFLICT DO NOTHING only adds new rows.
normalized_lemma must follow the VOC-01 normalization rules (see norm()).
"""
import uuid, unicodedata, json, sys
NS = uuid.UUID('6f1c1e2a-3b4d-4e5f-8a9b-0c1d2e3f4a5b')
def uid(name): return str(uuid.uuid5(NS, name))
def q(v):
    if v is None: return 'null'
    if isinstance(v, bool): return 'true' if v else 'false'
    if isinstance(v, (int,)): return str(v)
    return "'" + str(v).replace("'", "''") + "'"
def norm(lemma):
    # Must match com.linguaflow.vocabulary.LemmaNormalizer (LemmaNormalizerTests checks the seed)
    s = unicodedata.normalize('NFC', ' '.join(lemma.split()).lower())
    for art in ('der ', 'die ', 'das '):
        if s.startswith(art):
            s = s[len(art):]
            break
    return s.replace('ß', 'ss')

USER = uid('demo-user')
G = {'der':'MASCULINE','die':'FEMININE','das':'NEUTER'}
# (lemma, pos, translation, plural, cefr, status, extra, tags)
N = lambda art, w, tr, pl, lvl, st='LEARNING', tags=(): (f'{art} {w}', 'NOUN', tr, pl, lvl, st, {}, tags, G[art])
V = lambda w, tr, prät, p2, aux, lvl, st='LEARNING', tags=(): (w, 'VERB', tr, None, lvl, st, {'praeteritum':prät,'partizip2':p2,'auxiliary':aux}, tags, None)
A = lambda w, pos, tr, lvl, st='LEARNING', tags=(): (w, pos, tr, None, lvl, st, {}, tags, None)
WORDS = [
 N('der','Hund','dog','die Hunde','A1','LEARNED',('Tiere',)),
 N('die','Katze','cat','die Katzen','A1','LEARNED',('Tiere',)),
 N('das','Pferd','horse','die Pferde','A1','LEARNING',('Tiere',)),
 N('der','Vogel','bird','die Vögel','A1','NEW',('Tiere',)),
 N('die','Maus','mouse','die Mäuse','A1','LEARNING',('Tiere',)),
 N('das','Brot','bread','die Brote','A1','LEARNED',('Essen',)),
 N('der','Apfel','apple','die Äpfel','A1','LEARNED',('Essen',)),
 N('die','Milch','milk',None,'A1','LEARNING',('Essen',)),
 N('der','Käse','cheese','die Käse','A1','LEARNING',('Essen',)),
 N('das','Ei','egg','die Eier','A1','NEW',('Essen',)),
 N('die','Kartoffel','potato','die Kartoffeln','A1','LEARNING',('Essen',)),
 N('das','Frühstück','breakfast','die Frühstücke','A1','LEARNING',('Essen','Alltag')),
 N('das','Haus','house','die Häuser','A1','LEARNED',('Alltag',)),
 N('die','Wohnung','apartment','die Wohnungen','A1','LEARNING',('Alltag',)),
 N('der','Tisch','table','die Tische','A1','LEARNED',('Alltag',)),
 N('der','Stuhl','chair','die Stühle','A1','LEARNING',('Alltag',)),
 N('das','Fenster','window','die Fenster','A1','LEARNING',('Alltag',)),
 N('die','Tür','door','die Türen','A1','LEARNING',('Alltag',)),
 N('die','Straße','street','die Straßen','A1','LEARNING',('Alltag',)),
 N('der','Bahnhof','train station','die Bahnhöfe','A2','NEW',('Alltag',)),
 N('die','Arbeit','work','die Arbeiten','A1','LEARNING',('Alltag',)),
 N('der','Termin','appointment','die Termine','A2','NEW',('Alltag',)),
 N('die','Rechnung','bill, invoice','die Rechnungen','A2','NEW',('Alltag',)),
 N('das','Wetter','weather',None,'A1','LEARNING'),
 N('die','Zeit','time','die Zeiten','A1','LEARNED'),
 N('der','Freund','friend','die Freunde','A1','LEARNED'),
 N('die','Erfahrung','experience','die Erfahrungen','B1','NEW'),
 N('die','Entscheidung','decision','die Entscheidungen','B1','NEW'),
 V('gehen','to go','ging','gegangen','sein','A1','LEARNED',('Verben',)),
 V('kommen','to come','kam','gekommen','sein','A1','LEARNED',('Verben',)),
 V('fahren','to drive, to travel','fuhr','gefahren','sein','A1','LEARNING',('Verben',)),
 V('essen','to eat','aß','gegessen','haben','A1','LEARNED',('Verben','Essen')),
 V('trinken','to drink','trank','getrunken','haben','A1','LEARNING',('Verben','Essen')),
 V('schlafen','to sleep','schlief','geschlafen','haben','A1','LEARNING',('Verben',)),
 V('arbeiten','to work','arbeitete','gearbeitet','haben','A1','LEARNING',('Verben','Alltag')),
 V('helfen','to help','half','geholfen','haben','A2','LEARNING',('Verben',)),
 V('vergessen','to forget','vergaß','vergessen','haben','A2','NEW',('Verben',)),
 V('verstehen','to understand','verstand','verstanden','haben','A2','LEARNING',('Verben',)),
 V('sich erinnern','to remember','erinnerte sich','sich erinnert','haben','B1','NEW',('Verben',)),
 V('entscheiden','to decide','entschied','entschieden','haben','B1','NEW',('Verben',)),
 A('schön','ADJECTIVE','beautiful, nice','A1','LEARNED'),
 A('groß','ADJECTIVE','big, tall','A1','LEARNED'),
 A('klein','ADJECTIVE','small','A1','LEARNED'),
 A('schnell','ADJECTIVE','fast','A1','LEARNING'),
 A('müde','ADJECTIVE','tired','A1','LEARNING'),
 A('wichtig','ADJECTIVE','important','A2','LEARNING'),
 A('gestern','ADVERB','yesterday','A1','LEARNED'),
 A('oft','ADVERB','often','A1','LEARNING'),
 A('trotzdem','ADVERB','nevertheless','B1','NEW'),
 A('mit','PREPOSITION','with (+ dative)','A1','LEARNING'),
 A('wegen','PREPOSITION','because of (+ genitive)','B1','NEW'),
 A('weil','CONJUNCTION','because (verb at the end)','A2','LEARNING'),
 A('Guten Appetit','PHRASE','enjoy your meal','A1','LEARNED',('Essen',)),
]
EXAMPLES = {
 'der Hund': [('Der Hund bellt laut.','The dog barks loudly.'),('Wir haben einen Hund.','We have a dog.')],
 'die Katze': [('Die Katze schläft auf dem Sofa.','The cat is sleeping on the sofa.')],
 'der Apfel': [('Ich esse jeden Tag einen Apfel.','I eat an apple every day.')],
 'das Haus': [('Das Haus hat einen großen Garten.','The house has a big garden.')],
 'gehen': [('Ich gehe heute ins Kino.','I am going to the cinema today.'),('Gestern bin ich nach Hause gegangen.','Yesterday I went home.')],
 'helfen': [('Kannst du mir helfen?','Can you help me?'),('Ich helfe meinem Bruder.','I help my brother.')],
 'vergessen': [('Ich habe meinen Schlüssel vergessen.','I forgot my key.')],
 'mit': [('Ich fahre mit dem Bus.','I go by bus.')],
 'weil': [('Ich bleibe zu Hause, weil ich müde bin.','I am staying home because I am tired.')],
 'wegen': [('Wegen des Wetters bleiben wir drinnen.','Because of the weather we stay inside.')],
}
TOPICS = [
 ('Präsens','praesens','Present tense conjugation of regular and irregular verbs.','A1','LEARNED'),
 ('Artikel und Genus','artikel-und-genus','Definite and indefinite articles; der, die, das.','A1','MASTERED'),
 ('Akkusativ','akkusativ','Direct objects; den/einen for masculine nouns.','A1','LEARNED'),
 ('Dativ','dativ','Indirect objects and dative prepositions (mit, bei, nach, von, zu ...).','A2','LEARNING'),
 ('Perfekt','perfekt','Past tense with haben/sein + Partizip II.','A2','LEARNING'),
 ('Modalverben','modalverben','können, müssen, dürfen, sollen, wollen, mögen.','A2','LEARNING'),
 ('Trennbare Verben','trennbare-verben','Separable prefixes: ankommen, aufstehen, einkaufen.','A2','NEW'),
 ('Nebensätze mit weil und dass','nebensaetze-weil-dass','Subordinate clauses: the conjugated verb goes to the end.','A2','NEW'),
 ('Präteritum','praeteritum','Simple past, mainly for written German and sein/haben/modal verbs.','B1','NEW'),
 ('Adjektivdeklination','adjektivdeklination','Adjective endings after der-words, ein-words and without article.','B1','NEW'),
]
out = []
w = out.append
w("-- Dev-only demo data (DB-05). Loaded only when the dev profile adds classpath:db/dev to")
w("-- spring.flyway.locations; never present in test or prod databases.")
w("-- Repeatable migration: Flyway reruns it when this file changes. Every insert uses fixed IDs")
w("-- and ON CONFLICT DO NOTHING, so reruns add new rows but never overwrite edits to demo data.")
w("--")
w("-- Login (once AUTH lands): demo@linguaflow.local / demo-password-123")
w("-- Generated by backend/tools/generate_dev_seed.py; edit the script, not this file.")
w("-- normalized_lemma follows the VOC-01 rules (lower-case, article")
w("-- removed, NFC, ß -> ss).")
w("")
w("insert into users (id, email, password_hash, display_name, interface_language_code, timezone, email_verified_at)")
w(f"values ({q(USER)}, 'demo@linguaflow.local',")
w("        '{bcrypt}$2a$10$5sw97BLS8tqEufANulr7bekxBRN7WFSZrmex2hRdbgfczNZHs3F6O',")
w("        'Demo Learner', 'en', 'Europe/Berlin', now())")
w("on conflict do nothing;")
w("")
w("insert into user_languages (user_id, language_code, explanation_language_code, self_assessed_level, active,")
w("                            daily_review_goal, new_cards_per_day, preferred_exercise_types)")
w(f"values ({q(USER)}, 'de', 'en', 'A2', true, 40, 10, '{{FILL_BLANK,MULTIPLE_CHOICE,WORD_ORDER}}')")
w("on conflict do nothing;")
w("")
tags = sorted({t for x in WORDS for t in x[7]})
w("insert into tags (id, user_id, language_code, name) values")
w(",\n".join(f"    ({q(uid('tag:'+t))}, {q(USER)}, 'de', {q(t)})" for t in tags))
w("on conflict do nothing;")
w("")
w("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, part_of_speech, translation,")
w("                              gender, plural, extra, cefr_level, status, ai_status, ai_generated_fields) values")
rows=[]
for (lemma,pos,tr,pl,lvl,st,extra,tg,gender) in WORDS:
    ai = ['translation','partOfSpeech','cefrLevel'] + (['gender','plural'] if pos=='NOUN' else []) + (['extra'] if extra else [])
    arr = '{' + ','.join(ai) + '}'
    rows.append(f"    ({q(uid('word:'+lemma))}, {q(USER)}, 'de', {q(lemma)}, {q(norm(lemma))}, {q(pos)}, {q(tr)}, {q(gender)}, {q(pl)}, {q(json.dumps(extra, ensure_ascii=False))}, {q(lvl)}, {q(st)}, 'READY', {q(arr)})")
w(",\n".join(rows))
w("on conflict do nothing;")
w("")
w("insert into vocabulary_examples (id, vocabulary_item_id, sentence, translation, position, ai_generated) values")
rows=[]
for lemma, exs in EXAMPLES.items():
    for i,(s_,t_) in enumerate(exs):
        rows.append(f"    ({q(uid(f'example:{lemma}:{i}'))}, {q(uid('word:'+lemma))}, {q(s_)}, {q(t_)}, {i}, true)")
w(",\n".join(rows))
w("on conflict do nothing;")
w("")
w("insert into vocabulary_item_tags (vocabulary_item_id, tag_id) values")
w(",\n".join(f"    ({q(uid('word:'+x[0]))}, {q(uid('tag:'+t))})" for x in WORDS for t in x[7]))
w("on conflict do nothing;")
w("")
w("insert into grammar_topics (id, user_id, language_code, title, normalized_title, description, cefr_level, status) values")
w(",\n".join(f"    ({q(uid('topic:'+slug))}, {q(USER)}, 'de', {q(t)}, {q(norm(t))}, {q(d)}, {q(l)}, {q(st)})" for t,slug,d,l,st in TOPICS))
w("on conflict do nothing;")
w("")
w("-- Status history: every topic was created as NEW; the ones that moved on record the change")
rows=[]
for t,slug,d,l,st in TOPICS:
    rows.append(f"    ({q(uid('history:'+slug+':created'))}, {q(uid('topic:'+slug))}, null, 'NEW', 'CREATED', now() - interval '30 days')")
    if st != 'NEW':
        reason = 'MASTERY_RULE' if st=='MASTERED' else 'MANUAL'
        rows.append(f"    ({q(uid('history:'+slug+':'+st))}, {q(uid('topic:'+slug))}, 'NEW', {q(st)}, {q(reason)}, now() - interval '7 days')")
w("insert into grammar_topic_status_history (id, grammar_topic_id, from_status, to_status, reason, changed_at) values")
w(",\n".join(rows))
w("on conflict do nothing;")
w("")
S1, S2 = uid('session:1'), uid('session:2')
w("insert into study_sessions (id, user_id, language_code, title, studied_on, notes, active) values")
w(f"    ({q(S1)}, {q(USER)}, 'de', 'Lektion 1: Tiere und Essen', current_date - 14, 'Nouns with articles, Akkusativ.', false),")
w(f"    ({q(S2)}, {q(USER)}, 'de', 'Lektion 2: Alltag und Verben', current_date - 2, 'Perfekt and Dativ prepositions.', true)")
w("on conflict do nothing;")
w("")
l1 = [x[0] for x in WORDS if set(x[7]) & {'Tiere','Essen'}]
l2 = [x[0] for x in WORDS if set(x[7]) & {'Alltag','Verben'} and x[0] not in l1]
w("insert into session_vocabulary (study_session_id, vocabulary_item_id) values")
w(",\n".join([f"    ({q(S1)}, {q(uid('word:'+x))})" for x in l1] + [f"    ({q(S2)}, {q(uid('word:'+x))})" for x in l2]))
w("on conflict do nothing;")
w("")
w("insert into session_grammar (study_session_id, grammar_topic_id) values")
g1=['artikel-und-genus','akkusativ','praesens']; g2=['perfekt','dativ','modalverben']
w(",\n".join([f"    ({q(S1)}, {q(uid('topic:'+s))})" for s in g1] + [f"    ({q(S2)}, {q(uid('topic:'+s))})" for s in g2]))
w("on conflict do nothing;")
sys.stdout.write("\n".join(out) + "\n")
print(f"words={len(WORDS)} examples={sum(len(v) for v in EXAMPLES.values())} topics={len(TOPICS)} tags={len(tags)} s1={len(l1)} s2={len(l2)}", file=sys.stderr)
