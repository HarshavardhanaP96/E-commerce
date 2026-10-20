import { describe, expect, it } from "vitest";
import { MAX_GALLERY, splitPictures } from "./productFormData";

const file = (name: string, size = 10) => new File([new Uint8Array(size)], name, { type: "image/png" });

describe("splitPictures", () => {
  it("makes the first picture the main photo and the rest the gallery", () => {
    const [a, b, c] = [file("a"), file("b"), file("c")];
    expect(splitPictures([a, b, c])).toEqual({ photo: a, images: [b, c] });
  });

  it("with one picture, sets only the main photo", () => {
    const a = file("a");
    expect(splitPictures([a])).toEqual({ photo: a, images: [] });
  });

  it("with no pictures, clears the pending upload", () => {
    expect(splitPictures([])).toEqual({ photo: undefined, images: [] });
  });

  it("accepts one main photo plus a full gallery", () => {
    const files = Array.from({ length: MAX_GALLERY + 1 }, (_, i) => file(`f${i}`));
    expect(splitPictures(files)).toEqual({ photo: files[0], images: files.slice(1) });
  });

  it("rejects more pictures than a main photo plus a full gallery", () => {
    const files = Array.from({ length: MAX_GALLERY + 2 }, (_, i) => file(`f${i}`));
    expect(splitPictures(files)).toEqual({ error: `You can add up to ${MAX_GALLERY + 1} pictures` });
  });

  it("rejects a picture over 5 MB", () => {
    expect(splitPictures([file("a"), file("big", 5 * 1024 * 1024 + 1)])).toEqual({
      error: "Each image must be 5 MB or smaller",
    });
  });
});
