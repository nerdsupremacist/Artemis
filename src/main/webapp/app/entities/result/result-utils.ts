import { Result } from 'app/entities/result/result.model';

/**
 * Check if the given result was initialized and has a score
 *
 * @param result
 */
export const initializedResultWithScore = (result: Result | null) => {
    return result != null && (result.score || result.score === 0);
};
