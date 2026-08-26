/**
 * Server-side quiz answer key.
 *
 * The client fetches quizzes.json (which necessarily contains the correct
 * answers, since it renders and grades locally for immediate feedback), so
 * "was this answer correct" can never be trusted from the client. This module
 * keeps an independent server-side copy of just the answers, synced from the
 * same quizzes.json, and stored in Firestore so reward claims don't depend on
 * an external fetch at claim time.
 *
 * Quiz ids are only unique WITHIN a category (every category has a quiz "1"),
 * so the lookup key is `${category}:${quizId}`.
 */

export const QUIZ_DATA_URL = "https://quizzes-b446b.web.app/quizzes.json";
export const ANSWER_KEY_COLLECTION = "config";
export const ANSWER_KEY_DOC = "quizAnswerKey";

export interface QuizAnswerKey {
  version: number;
  /** `${category}:${quizId}` -> correctAnswer index per question, by position. */
  answers: Record<string, number[]>;
  syncedAt: number;
}

interface RawQuizJson {
  version: number;
  categories: Array<{
    name: string;
    quizzes: Array<{
      id: string;
      questions: Array<{correctAnswer: number}>;
    }>;
  }>;
}

export function answerKeyLookupKey(category: string, quizId: string): string {
  return `${category}:${quizId}`;
}

/** Reduces the full quiz JSON to just the answer indices. Pure. */
export function buildAnswerKey(raw: RawQuizJson): QuizAnswerKey {
  const answers: Record<string, number[]> = {};

  for (const category of raw.categories || []) {
    for (const quiz of category.quizzes || []) {
      const key = answerKeyLookupKey(category.name, quiz.id);
      answers[key] = (quiz.questions || []).map((q) => Number(q.correctAnswer));
    }
  }

  return {
    version: Number(raw.version) || 0,
    answers,
    syncedAt: Date.now(),
  };
}

export async function fetchQuizAnswerKey(url: string = QUIZ_DATA_URL): Promise<QuizAnswerKey> {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Quiz data fetch failed: ${response.status}`);
  }
  const raw = (await response.json()) as RawQuizJson;
  return buildAnswerKey(raw);
}

/**
 * Grades one answer against the key. Returns null when the question can't be
 * found at all - callers should treat that as "cannot verify" and refuse to
 * award, rather than guessing.
 */
export function gradeAnswer(
  key: QuizAnswerKey,
  category: string,
  quizId: string,
  questionIndex: number,
  selectedAnswer: number
): boolean | null {
  const questionAnswers = key.answers[answerKeyLookupKey(category, quizId)];
  if (!questionAnswers) return null;
  if (!Number.isInteger(questionIndex) || questionIndex < 0 || questionIndex >= questionAnswers.length) {
    return null;
  }
  return questionAnswers[questionIndex] === selectedAnswer;
}
