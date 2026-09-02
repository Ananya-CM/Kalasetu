const functions = require("firebase-functions");
const admin = require("firebase-admin");
const Groq = require("groq-sdk");

admin.initializeApp();
const db = admin.firestore();

/**
 * Calls Groq's chat completion API, trying each model in order
 * until one succeeds.
 * @param {string} prompt The prompt to send to the model.
 * @param {number} maxTokens Maximum tokens to generate.
 * @param {number} temperature Sampling temperature.
 * @return {Promise<string>} The generated text.
 */
async function generateWithFallback(prompt, maxTokens, temperature) {
  const groq = new Groq({apiKey: process.env.GROQ_API_KEY});
  const models = ["openai/gpt-oss-120b", "openai/gpt-oss-20b"];

  let lastError;
  for (const model of models) {
    try {
      const completion = await groq.chat.completions.create({
        messages: [{role: "user", content: prompt}],
        model: model,
        temperature: temperature,
        max_tokens: maxTokens,
      });
      return completion.choices[0].message.content;
    } catch (error) {
      console.error(`Groq API error with model ${model}:`, error.message);
      lastError = error;
    }
  }
  throw lastError;
}

/**
 * Trims text to end at the last complete sentence, avoiding
 * mid-sentence cutoffs from token limits.
 * @param {string} text The text to trim.
 * @return {string} The trimmed text.
 */
function trimToLastSentence(text) {
  const lastPunctuation = Math.max(
      text.lastIndexOf("."),
      text.lastIndexOf("!"),
      text.lastIndexOf("?"),
  );
  if (lastPunctuation > text.length * 0.5) {
    return text.substring(0, lastPunctuation + 1);
  }
  return text;
}

// --- 1. Add Product ---
exports.addProduct = functions.https.onCall(async (request) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "Please login.");
  }
  const {name, description, price, imageUrl, category} = request.data;

  if (!name || !description || !price || !imageUrl || !category) {
    throw new functions.https.HttpsError(
        "invalid-argument",
        "All product fields are required.",
    );
  }

  const product = {
    name,
    description,
    price,
    imageUrl,
    category,
    userId: request.auth.uid,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  };
  const docRef = await db.collection("products").add(product);
  return {success: true, id: docRef.id};
});

// --- Generate AI Story for a Product (real LLM call via Groq) ---
exports.generateStory = functions.https.onCall(async (request) => {
  const {productName, craftType, region} = request.data;

  if (!productName || !craftType || !region) {
    throw new functions.https.HttpsError(
        "invalid-argument",
        "productName, craftType, and region are all required.",
    );
  }

  const prompt =
    "Write a warm, 3-4 sentence heritage story for an artisan " +
    "product.\n" +
    `Craft type: ${craftType}\n` +
    `Region of origin: ${region}\n` +
    `Product name: ${productName}\n\n` +
    "Keep it authentic and grounded - do not invent specific " +
    "historical dates, named events, or family details that " +
    "weren't provided. Focus on the craft tradition and " +
    "regional heritage in general terms.";

  try {
    const story = await generateWithFallback(prompt, 300, 0.7);
    return {story: trimToLastSentence(story)};
  } catch (error) {
    console.error("Groq API error:", error);
    throw new functions.https.HttpsError(
        "internal",
        "Story generation failed. Please try again.",
    );
  }
});

// --- Generate Marketing Caption (real LLM call via Groq) ---
exports.generateCaption = functions.https.onCall(async (request) => {
  const {productDescription} = request.data;

  if (!productDescription) {
    throw new functions.https.HttpsError(
        "invalid-argument",
        "productDescription is required.",
    );
  }

  const prompt =
    "Write a short, engaging social media caption (2-3 sentences, " +
    "include 3-5 relevant hashtags) for an Instagram/Facebook post " +
    "promoting this handmade product:\n\n" +
    productDescription;

  try {
    const caption = await generateWithFallback(prompt, 300, 0.8);
    return {caption: trimToLastSentence(caption)};
  } catch (error) {
    console.error("Groq API error:", error);
    throw new functions.https.HttpsError(
        "internal",
        "Caption generation failed. Please try again.",
    );
  }
});

// --- Search YouTube Videos ---
exports.searchYouTubeVideos = functions.https.onCall(async (request) => {
  const apiKey = process.env.YOUTUBE_API_KEY;
  const {craftType} = request.data;

  if (!craftType) {
    throw new functions.https.HttpsError(
        "invalid-argument",
        "craftType is required.",
    );
  }

  const query = encodeURIComponent(`${craftType} tutorial art craft`);
  const url =
    "https://www.googleapis.com/youtube/v3/search" +
    `?part=snippet&type=video&maxResults=6&q=${query}&key=${apiKey}`;

  try {
    const response = await fetch(url);
    const data = await response.json();

    if (data.error) {
      console.error("YouTube API error:", data.error);
      throw new functions.https.HttpsError(
          "internal",
          "YouTube search failed.",
      );
    }

    const videos = data.items.map((item) => ({
      videoId: item.id.videoId,
      title: item.snippet.title,
      thumbnail: item.snippet.thumbnails.medium.url,
      channelTitle: item.snippet.channelTitle,
    }));

    return {videos};
  } catch (error) {
    console.error("Error fetching YouTube videos:", error);
    throw new functions.https.HttpsError(
        "internal",
        "Failed to fetch videos. Please try again.",
    );
  }
});

// --- Edit Product (owner only) ---
exports.editProduct = functions.https.onCall(async (request) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "Please login.");
  }
  const {productId, name, description, price, category} = request.data;

  if (!productId || !name || !description || !price || !category) {
    throw new functions.https.HttpsError(
        "invalid-argument",
        "All fields are required.",
    );
  }

  const doc = await db.collection("products").doc(productId).get();
  if (!doc.exists) {
    throw new functions.https.HttpsError("not-found", "Product not found.");
  }
  if (doc.data().userId !== request.auth.uid) {
    throw new functions.https.HttpsError(
        "permission-denied",
        "Only the owner can edit this product.",
    );
  }

  await db.collection("products").doc(productId).update({
    name, description, price, category,
  });
  return {success: true};
});

// --- Delete Product (owner only) ---
exports.deleteProduct = functions.https.onCall(async (request) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "Please login.");
  }
  const {productId} = request.data;
  if (!productId) {
    throw new functions.https.HttpsError(
        "invalid-argument",
        "productId is required.",
    );
  }

  const doc = await db.collection("products").doc(productId).get();
  if (!doc.exists) {
    throw new functions.https.HttpsError("not-found", "Product not found.");
  }
  if (doc.data().userId !== request.auth.uid) {
    throw new functions.https.HttpsError(
        "permission-denied",
        "Only the owner can delete this product.",
    );
  }

  await db.collection("products").doc(productId).delete();
  return {success: true};
});
