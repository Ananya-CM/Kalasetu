const firebaseConfig = {
  apiKey: "AIzaSyDM-RDV4UNIt2sjagsS8-zqZA5SShX2QoI",
  authDomain: "kalasetu-25a17.firebaseapp.com",
  projectId: "kalasetu-25a17",
  storageBucket: "kalasetu-25a17.firebasestorage.app",
  messagingSenderId: "930750254015",
  appId: "1:930750254015:web:3bab449ca32c1b5f396f0a"
};
firebase.initializeApp(firebaseConfig);
const auth = firebase.auth();
const db = firebase.firestore();

const urlParams = new URLSearchParams(window.location.search);
const productId = urlParams.get('productId');
const fromCart = urlParams.get('fromCart') === '1';

const productDetails = document.getElementById('product-details');
const form = document.getElementById('checkout-form');

let checkoutItems = [];
let currentUser = null;

const ORDER_SERVICE_URL = (window.KALASETU_CONFIG && window.KALASETU_CONFIG.orderServiceUrl) || "";
const RAZORPAY_CHECKOUT_SRC = "https://checkout.razorpay.com/v1/checkout.js";
const PAYMENT_WINDOW_SECONDS = 600;

// One key per checkout attempt, reused on retries so the service never creates a duplicate order.
let checkoutAttemptKey = null;

auth.onAuthStateChanged(user => {
  if (!user) {
    productDetails.innerHTML = "<p>Please log in to checkout. <a href='index.html'>Go to login</a></p>";
    form.style.display = "none";
    return;
  }
  currentUser = user;
  init();
});

async function fetchProduct(id) {
  try {
    const doc = await db.collection('products').doc(id).get();
    return doc.exists ? { id: doc.id, ...doc.data() } : null;
  } catch (error) {
    console.error("Error fetching product:", error);
    return null;
  }
}

async function fetchCartItems() {
  try {
    const doc = await db.collection('carts').doc(currentUser.uid).get();
    return doc.exists ? (doc.data().items || []) : [];
  } catch (error) {
    console.error("Error fetching cart:", error);
    return [];
  }
}

async function init() {
  if (fromCart) {
    checkoutItems = await fetchCartItems();
    if (checkoutItems.length === 0) {
      productDetails.innerHTML = "<p>Your cart is empty.</p>";
      form.style.display = "none";
      return;
    }
  } else if (productId) {
    const product = await fetchProduct(productId);
    if (!product) {
      productDetails.innerHTML = "<p>Product not found.</p>";
      form.style.display = "none";
      return;
    }
    checkoutItems = [{ ...product, quantity: 1 }];
  } else {
    productDetails.innerHTML = "<p>No product or cart selected for checkout.</p>";
    form.style.display = "none";
    return;
  }

  renderCheckoutItems();
}

function renderCheckoutItems() {
  const total = checkoutItems.reduce((sum, item) => sum + (item.price * (item.quantity || 1)), 0);
  let itemsHtml = checkoutItems.map(item => `
    <div style="display:flex; justify-content:space-between; padding:8px 0; border-bottom:1px solid #eee;">
      <span>${item.name} × ${item.quantity || 1}</span>
      <span>₹${(item.price * (item.quantity || 1)).toFixed(2)}</span>
    </div>
  `).join('');

  productDetails.innerHTML = `
    <h2>Order Summary</h2>
    ${itemsHtml}
    <div style="margin-top:12px; font-weight:700; font-size:18px; text-align:right;">
      Total: ₹${total.toFixed(2)}
    </div>
  `;
}

async function saveOrder(formData) {
  const total = checkoutItems.reduce((sum, item) => sum + (item.price * (item.quantity || 1)), 0);

  const order = {
    userId: currentUser.uid,
    userEmail: currentUser.email,
    items: checkoutItems.map(item => ({
      productId: item.id,
      name: item.name,
      price: item.price,
      quantity: item.quantity || 1
    })),
    total: total,
    shippingName: formData.name,
    shippingEmail: formData.email,
    shippingAddress: formData.address,
    status: "placed",
    createdAt: firebase.firestore.FieldValue.serverTimestamp()
  };

  const docRef = await db.collection('orders').add(order);

  if (fromCart) {
    await clearCart();
  }

  return docRef.id;
}

async function clearCart() {
  await db.collection('carts').doc(currentUser.uid).set({
    userId: currentUser.uid,
    items: [],
    updatedAt: firebase.firestore.FieldValue.serverTimestamp()
  });
}

// ===== Online payment through the order service =====

class OrderServiceError extends Error {
  constructor(message, code) {
    super(message);
    this.code = code;
  }
}

async function callOrderService(path, { method = "GET", body, headers = {} } = {}) {
  const idToken = await currentUser.getIdToken();
  const response = await fetch(ORDER_SERVICE_URL + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${idToken}`,
      ...headers
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new OrderServiceError(payload.detail || "Request failed", payload.code);
  }
  return payload;
}

function loadRazorpayCheckout() {
  if (window.Razorpay) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = RAZORPAY_CHECKOUT_SRC;
    script.onload = resolve;
    script.onerror = () => reject(new OrderServiceError("Could not load the payment window.", "CHECKOUT_LOAD_FAILED"));
    document.body.appendChild(script);
  });
}

function collectPayment(order, formData) {
  return new Promise((resolve, reject) => {
    const razorpay = new window.Razorpay({
      key: order.gatewayKeyId,
      amount: order.totalPaise,
      currency: order.currency,
      order_id: order.gatewayOrderId,
      name: "Kalasetu",
      description: `Order ${order.orderId}`,
      prefill: { name: formData.name, email: formData.email },
      theme: { color: "#33a08d" },
      timeout: PAYMENT_WINDOW_SECONDS,
      handler: resolve,
      modal: {
        ondismiss: () => reject(new OrderServiceError("Payment was cancelled.", "PAYMENT_CANCELLED"))
      }
    });
    // A failed attempt is not final: the buyer can retry inside the same window.
    razorpay.on("payment.failed", (response) => console.warn("Payment attempt failed:", response.error));
    razorpay.open();
  });
}

async function createServiceOrder(formData) {
  if (!checkoutAttemptKey) checkoutAttemptKey = crypto.randomUUID();
  return callOrderService("/api/v1/orders", {
    method: "POST",
    headers: { "Idempotency-Key": checkoutAttemptKey },
    body: {
      items: checkoutItems.map(item => ({ productId: item.id, quantity: item.quantity || 1 })),
      shipping: formData
    }
  });
}

async function placeOrderWithPayment(formData) {
  let order = await createServiceOrder(formData);
  if (order.status === "EXPIRED") {
    // The previous attempt timed out; start a fresh order.
    checkoutAttemptKey = null;
    order = await createServiceOrder(formData);
  }

  if (order.status === "PENDING_PAYMENT") {
    await loadRazorpayCheckout();
    const payment = await collectPayment(order, formData);
    order = await callOrderService(`/api/v1/orders/${encodeURIComponent(order.orderId)}/payment`, {
      method: "POST",
      body: {
        gatewayPaymentId: payment.razorpay_payment_id,
        signature: payment.razorpay_signature
      }
    });
  }

  if (order.status !== "PAID") {
    throw new OrderServiceError(`Order is ${order.status}.`, "PAYMENT_NOT_COMPLETED");
  }
  if (fromCart) {
    await clearCart();
  }
  return order.orderId;
}

function userMessageFor(error) {
  switch (error.code) {
    case "PAYMENT_CANCELLED":
      return "Payment was cancelled. You can try again.";
    case "OUT_OF_STOCK":
      return "Sorry, one of the items just went out of stock.";
    case "PRODUCT_NOT_FOUND":
      return "One of the items is no longer available.";
    default:
      return "Something went wrong placing your order. Please try again.";
  }
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();

  const submitBtn = form.querySelector('button[type="submit"]');
  submitBtn.disabled = true;
  submitBtn.textContent = ORDER_SERVICE_URL ? "Processing payment..." : "Placing order...";

  const formData = {
    name: document.getElementById('name').value.trim(),
    email: document.getElementById('email').value.trim(),
    address: document.getElementById('address').value.trim()
  };

  try {
    const orderId = ORDER_SERVICE_URL
      ? await placeOrderWithPayment(formData)
      : await saveOrder(formData);
    const confirmationText = ORDER_SERVICE_URL
      ? "Payment received. Your order is confirmed."
      : "Your order has been saved. The seller can view it in their records.";
    document.querySelector(".checkout-container").innerHTML = `
      <div class="order-confirmation" style="margin-top:24px;text-align:center;">
        <h2 style="color:#33a08d;">Order Placed!</h2>
        <p>Order ID: <strong>${orderId}</strong></p>
        <p>${confirmationText}</p>
        <a href="index.html" style="display:inline-block;margin-top:20px;padding:12px 24px;background:#33a08d;color:#fff;text-decoration:none;border-radius:6px;">Back to Home</a>
      </div>
    `;
  } catch (error) {
    if (error.code !== "PAYMENT_CANCELLED") {
      console.error("Error placing order:", error);
    }
    submitBtn.disabled = false;
    submitBtn.textContent = "Complete Purchase";
    alert(userMessageFor(error));
  }
});