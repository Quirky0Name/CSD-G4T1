from research_evaluation.impact.schemas import Claim, Sentence, kept, prose

CLAIMS = [
    Claim(text="The paper was retracted.", passages=[]),
    Claim(text="The data were fabricated.", passages=[]),
]


def fact(text: str, *claims: int) -> Sentence:
    return Sentence(text=text, kind="fact", claims=list(claims))


def test_sentences_are_joined_in_order():
    sentences = [fact("The paper was retracted.", 1), fact("Its data were fabricated.", 2)]

    assert prose(sentences, CLAIMS) == "The paper was retracted. Its data were fabricated."


def test_a_fact_with_no_claim_is_left_out():
    sentences = [fact("The paper was retracted.", 1), fact("Your draft is not affected.")]

    assert prose(sentences, CLAIMS) == "The paper was retracted."


def test_a_fact_naming_only_claims_that_dont_exist_is_left_out():
    sentences = [fact("The paper was retracted.", 1), fact("The whole paper is affected.", 0, 3)]

    assert prose(sentences, CLAIMS) == "The paper was retracted."


def test_advice_needs_no_claim():
    sentences = [
        fact("The paper was retracted.", 1),
        Sentence(text="Upload your draft.", kind="advice", claims=[]),
    ]

    assert prose(sentences, CLAIMS) == "The paper was retracted. Upload your draft."


def test_when_no_sentence_has_a_claim_all_are_kept_so_the_text_is_never_empty():
    sentences = [fact("Something changed."), fact("It may matter.")]

    assert prose(sentences, CLAIMS) == "Something changed. It may matter."


def test_spacing_around_sentences_is_tidied():
    sentences = [fact("  The paper was retracted. ", 1), fact("\nIts data were fabricated.", 2)]

    assert prose(sentences, CLAIMS) == "The paper was retracted. Its data were fabricated."


def test_kept_gives_the_sentences_the_stored_text_keeps():
    advice = Sentence(text="Upload your draft.", kind="advice", claims=[])
    sentences = [fact("The paper was retracted.", 1), fact("Your draft is not affected."), advice]

    assert kept(sentences, CLAIMS) == [sentences[0], advice]


def test_a_claim_states_no_absence_unless_the_model_says_so():
    assert Claim(text="The paper was retracted.", passages=[]).absence is False
    assert Claim.model_validate({"text": "No notice was given.", "passages": [], "absence": True}).absence
