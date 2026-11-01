import { Feed, marketingAPI } from "./marketingAPI";

export type FeedResponse = { success: boolean; feed: Feed };

/**
 * Admin → Marketing product feed, injected into the Marketing slice so it shares its cache: turning the feed on or
 * off and making a new address are one call each, and the returned feed replaces the cached one (no refetch).
 */
export const feedAPI = marketingAPI.injectEndpoints({
  endpoints: (builder) => ({
    setFeed: builder.mutation<FeedResponse, boolean>({
      query: (enabled) => ({ url: "feed", method: "PUT", body: { enabled } }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled;
          dispatch(putFeed(data.feed));
        } catch {
          // the card shows the error from the mutation result
        }
      },
    }),
    rotateFeed: builder.mutation<FeedResponse, void>({
      query: () => ({ url: "feed/rotate", method: "POST" }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled;
          dispatch(putFeed(data.feed));
        } catch {
          // the card shows the error from the mutation result
        }
      },
    }),
  }),
});

const putFeed = (feed: Feed) =>
  marketingAPI.util.updateQueryData("marketing", undefined, (draft) => {
    draft.feed = feed;
  });

export const { useSetFeedMutation, useRotateFeedMutation } = feedAPI;
