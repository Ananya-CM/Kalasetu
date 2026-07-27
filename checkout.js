// Initialize Firebase if not already done
// If your checkout.js is separate, ensure Firebase SDK and config are loaded here or in checkout.html
const firebaseConfig = {
  apiKey: "import.meta.env.VITE_FIREBASE_API_KEY",
  authDomain: "import.meta.env.VITE_FIREBASE_AUTH_DOMAIN",
  projectId: "import.meta.env.VITE_FIREBASE_PROJECT_ID",
  storageBucket: "import.meta.env.VITE_FIREBASE_STORAGE_BUCKET",
  messagingSenderId: "import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID",
  appId: "import.meta.env.VITE_FIREBASE_APP_ID"
};
firebase.initializeApp(firebaseConfig);
const db = firebase.firestore();

// Parse productId from URL params
const urlParams = new URLSearchParams(window.location.search);
const productId = urlParams.get('productId');

const productDetails = document.getElementById('product-details');
const form = document.getElementById('checkout-form');

async function fetchProduct(productId) {
  try {
    const docRef = db.collection('products').doc(productId);
    const doc = await docRef.get();
    if (doc.exists) {
      return { id: doc.id, ...doc.data() };
    } else {
      throw new Error("No such product!");
    }
  } catch (error) {
    console.error("Error fetching product:", error);
    return null;
  }
}

async function init() {
  if (!productId) {
    productDetails.innerHTML = "<p>Invalid product selected for checkout.</p>";
    return;
  }

  const product = await fetchProduct(productId);
  if (!product) {
    productDetails.innerHTML = "<p>Product not found.</p>";
    return;
  }

  productDetails.innerHTML = `
    <h2>${product.name}</h2>
    <p>Price: ₹${product.price}</p>
    <p>${product.description}</p>
  `;
}

init();

form.addEventListener('submit', (e) => {
  e.preventDefault();

  // Replace the entire container content with order confirmation
  document.querySelector(".checkout-container").innerHTML = `
    <div class="order-confirmation" style="margin-top:24px;text-align:center;">
      <h2 style="color:#33a08d;">Order Placed!</h2>
      <p>Your order has been successfully placed.<br>
      You will receive a confirmation email soon.</p>
      <a href="index.html" style="display:inline-block;margin-top:20px;padding:12px 24px;background:#33a08d;color:#fff;text-decoration:none;border-radius:6px;">Back to Home</a>
    </div>
  `;
});
