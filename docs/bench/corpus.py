"""Deterministic German-OCR-shaped corpus for the #290 search benchmark.

Shape (identical on both instances, seeded from the document index alone):
  * ~120 long compound nouns per document, drawn from a ~570k-combination space, so the
    global `content` term dictionary reaches OCR scale (~250k distinct terms) - that
    dictionary is what a prefixLength-0 Levenshtein automaton has to walk.
  * `schluessel` spelled `schlüssel` in 40% of the documents (high-frequency probe term).
  * `werkstattwagen` in exactly 5 documents (rare exact probe term).
  * 600 distinct terms within 2 edits of `werkstattwagen`, ALL errors after position 2,
    spread two documents apart each (dense fuzzy neighbourhood).
"""
import random

HEADS = """akten verwaltung betriebs liefer wartungs grund werks lager fracht zoll
steuer finanz handels bank versicherungs renten kranken pflege unfall haftpflicht
bau planungs vermessungs kataster liegenschafts wohnungs miet pacht erb schenkungs
gewerbe handwerks innungs kammer verbands genossenschafts stiftungs vereins partei
schul hochschul forschungs bibliotheks archiv museums theater konzert sport
verkehrs strassen schienen wasser luft hafen flug bahn bus
umwelt natur landschafts forst jagd fischerei berg wasserwirtschafts abfall
energie strom gas fernwaerme solar wind biomasse kraftwerks netz
gesundheits apotheken labor klinik reha kur arznei impf hygiene""".split()

MIDS = """kosten preis wert summen anteil quoten satz tarif gebuehren beitrags
vertrags urkunden protokoll bericht gutachten pruefungs revisions kontroll aufsichts
antrags bewilligungs genehmigungs zulassungs erlaubnis konzessions lizenz register
personal dienst arbeits tarif lohn gehalts urlaubs schicht einsatz
sicherheits schutz notfall alarm brand rettungs katastrophen evakuierungs melde
qualitaets norm standard zertifizierungs akkreditierungs eich kalibrier mess pruef
daten informations dokumenten archivierungs speicher uebertragungs netzwerk system software
projekt planungs entwicklungs bau montage inbetriebnahme abnahme gewaehrleistungs wartungs
beschaffungs vergabe ausschreibungs angebots bestell liefer transport lager versand""".split()

TAILS = """ordnung verordnung satzung richtlinie vorschrift bestimmung regelung anweisung
verzeichnis uebersicht aufstellung nachweis beleg quittung rechnung mahnung
verfahren ablauf prozess vorgang massnahme handlung taetigkeit leistung
abteilung referat sachgebiet stelle behoerde amt dienststelle einrichtung
unterlage akte mappe dossier sammlung bestand konvolut buendel
erklaerung stellungnahme mitteilung benachrichtigung ankuendigung hinweis merkblatt broschuere
zeitraum termin frist stichtag periode quartal halbjahr jahresabschluss
schluessel nummer kennzeichen bezeichnung benennung titel ueberschrift rubrik""".split()

FILLER = """der die das den dem des ein eine einer und oder aber sowie nach vor bei mit
fuer gegen ohne ueber unter zwischen wird werden wurde worden ist sind war waren
kann koennen muss muessen soll sollen darf duerfen gemaess laut entsprechend""".split()

DOMAIN = """aktenvermerk rechnungspruefung uebungsleitervertrag betriebskostenabrechnung
grundstuecksverkehrsgenehmigung wartungsprotokoll lieferantenrahmenvertrag
verwendungsnachweis zuwendungsbescheid bewirtschaftungsbefugnis""".split()

HIGH_FREQ = "schlüssel"
RARE = "werkstattwagen"
RARE_DOCS = [7, 613, 1229, 1845, 2461]
TYPO_AFTER2 = "werkstattwogen"   # 1 edit at index 10, "we" intact -> matches before AND after
TYPO_FIRST2 = "warkstattwagen"   # 1 edit at index 1  -> semantics-only probe

_ALPHA = "abcdefghijklmnopqrstuvwxyz"


def neighbourhood():
    """600 distinct terms within 2 edits of RARE, every error at index >= 2.

    Substitutions are confined to indices 2..9 so the probe typo at index 10
    (`werkstattwogen`) is provably NOT itself an indexed corpus term."""
    out, seen = [], {RARE}
    for p in range(2, 10):
        for c in _ALPHA:
            if c != RARE[p]:
                w = RARE[:p] + c + RARE[p + 1:]
                if w not in seen:
                    seen.add(w); out.append(w)
    rnd = random.Random(20260823)
    while len(out) < 600:
        p1, p2 = sorted(rnd.sample(range(2, 10), 2))
        c1, c2 = rnd.choice(_ALPHA), rnd.choice(_ALPHA)
        if c1 == RARE[p1] or c2 == RARE[p2]:
            continue
        w = RARE[:p1] + c1 + RARE[p1 + 1:p2] + c2 + RARE[p2 + 1:]
        if w not in seen:
            seen.add(w); out.append(w)
    return out


NEIGHBOURS = neighbourhood()


def placements(n_docs):
    """doc index -> list of neighbour terms it carries (each neighbour in 2 documents)."""
    m = {}
    for k, w in enumerate(NEIGHBOURS):
        for d in ((k * 5 + 3) % n_docs, (k * 5 + 3 + n_docs // 2) % n_docs):
            m.setdefault(d, []).append(w)
    return m


def page(i, extra):
    """~3 kB of German-looking prose for document i. Deterministic in i."""
    rnd = random.Random(1000000 + i)
    words = []
    for _ in range(120):
        words.append(rnd.choice(HEADS) + rnd.choice(MIDS) + rnd.choice(TAILS))
    words += [rnd.choice(FILLER) for _ in range(55)]
    words += [rnd.choice(DOMAIN) for _ in range(6)]
    if i % 5 < 2:                       # 40% of documents
        words.append(HIGH_FREQ)
    if i in RARE_DOCS:
        words.append(RARE)
    words += extra
    rnd.shuffle(words)
    lines, buf = [], []
    for w in words:
        buf.append(w)
        if len(buf) == 12:
            lines.append(" ".join(buf) + "."); buf = []
    if buf:
        lines.append(" ".join(buf) + ".")
    return "\n".join(lines) + "\n"


def title(i):
    rnd = random.Random(500000 + i)
    return "%s %04d %s" % (rnd.choice(TAILS).capitalize(), i,
                           rnd.choice(HEADS) + rnd.choice(MIDS) + rnd.choice(TAILS))
