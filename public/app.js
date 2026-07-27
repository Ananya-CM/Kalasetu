console.log("🚀 Kalasetu AI Assistant Loading...");

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
const storage = firebase.storage();
console.log("✅ Firebase Initialized");

let currentUser = null;
let allProducts = [];

function signup() {
  const email = document.getElementById("email").value.trim();
  const password = document.getElementById("password").value.trim();
  if (!email || !password) { showMessage("auth-message", "❌ Please enter email and password"); return; }
  if (password.length < 6) { showMessage("auth-message", "❌ Password must be 6+ characters"); return; }
  showMessage("auth-message", "⏳ Creating account...");
  auth.createUserWithEmailAndPassword(email, password)
    .then(userCredential => {
      const user = userCredential.user;
      db.collection("users").doc(user.uid).set({
        uid: user.uid, email: user.email, displayName: user.email.split('@')[0],
        name: "", craftType: "", location: "", experienceYears: 0,
        connectedUserIds: [], createdAt: firebase.firestore.FieldValue.serverTimestamp()
      });
      showDashboard(user);
    })
    .catch(error => showMessage("auth-message", "❌ " + error.message));
}

function login() {
  const email = document.getElementById("email").value.trim();
  const password = document.getElementById("password").value.trim();
  if (!email || !password) { showMessage("auth-message", "❌ Please enter email and password"); return; }
  showMessage("auth-message", "⏳ Logging in...");
  auth.signInWithEmailAndPassword(email, password)
    .then(userCredential => showDashboard(userCredential.user))
    .catch(error => showMessage("auth-message", "❌ " + error.message));
}

function logout() {
  auth.signOut().then(() => {
    currentUser = null;
    allProducts = [];
    document.getElementById("login-section").style.display = "block";
    document.getElementById("navbar").style.display = "none";
    document.getElementById("user-profile").style.display = "none";
    document.querySelectorAll(".section-content").forEach(s => s.style.display = "none");
    showMessage("auth-message", "✅ Logged out successfully");
  });
}

function showDashboard(user) {
  currentUser = user;
  document.getElementById("login-section").style.display = "none";
  document.getElementById("navbar").style.display = "block";
  document.getElementById("user-profile").style.display = "flex";
  const firstName = user.email.split('@')[0].split('.')[0];
  const capitalizedName = firstName.charAt(0).toUpperCase() + firstName.slice(1);
  document.getElementById("user-greeting").textContent = `Namaste, ${capitalizedName} ji!`;
  const dashboardGreeting = document.getElementById("dashboard-greeting");
  if (dashboardGreeting) dashboardGreeting.textContent = `Welcome back, ${capitalizedName}! Here's your artisan overview.`;
  const aiGreeting = document.getElementById("ai-greeting");
  if (aiGreeting) {
    const hour = new Date().getHours();
    const timeGreeting = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
    aiGreeting.textContent = `${timeGreeting}, ${capitalizedName}! I can help you create compelling stories for your crafts. What would you like to work on today?`;
  }
  showSection('dashboard');
  setupProductsListener();
  initializeAllSections();
}

auth.onAuthStateChanged(user => { if (user) showDashboard(user); });

function showSection(sectionId) {
  document.querySelectorAll(".section-content").forEach(section => section.style.display = "none");
  const targetSection = document.getElementById(sectionId + "-section");
  if (targetSection) {
    targetSection.style.display = "block";
    if (sectionId === 'products') refreshProductsList();
    else if (sectionId === 'cart') renderCart();
  }
  document.querySelectorAll(".nav-link").forEach(link => link.classList.remove("active"));
  const clickedLink = document.querySelector(`[onclick*="'${sectionId}'"]`);
  if (clickedLink && clickedLink.classList.contains('nav-link')) clickedLink.classList.add("active");
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function handleQuickAction(action) {
  switch(action) {
    case 'add-product': showSection('products'); setTimeout(() => showAddProduct(), 200); break;
    case 'generate-story': showSection('ai-storytelling'); break;
    case 'view-analytics': showSection('analytics'); break;
    case 'connect-artisans': showSection('community'); break;
  }
}

function setupProductsListener() {
  if (!currentUser) return;
  db.collection("products").orderBy("createdAt", "desc").onSnapshot(snapshot => {
    allProducts = [];
    snapshot.forEach(doc => allProducts.push({ id: doc.id, ...doc.data() }));
    refreshProductsList();
    updateUserStats();
  }, error => console.error("❌ Error in products listener:", error));
}

function refreshProductsList() { renderProducts(allProducts); }

function showAddProduct() {
  const form = document.getElementById("add-product-form");
  if (form) form.style.display = form.style.display === "none" ? "block" : "none";
}

function addProduct() {
  const user = auth.currentUser;
  if (!user) { showMessage("product-message", "❌ Please login first"); return; }
  const name = document.getElementById("product-name").value.trim();
  const desc = document.getElementById("product-desc").value.trim();
  const price = document.getElementById("product-price").value;
  const category = document.getElementById("product-category").value;
  const imageFile = document.getElementById("product-image").files[0];
  if (!name || !desc || !price || !category || !imageFile) { showMessage("product-message", "❌ Please fill all fields and select an image"); return; }
  if (isNaN(price) || price <= 0) { showMessage("product-message", "❌ Please enter a valid price"); return; }
  showMessage("product-message", "⏳ Uploading product image...");
  const ref = storage.ref("products/" + user.uid + "/" + Date.now() + "-" + imageFile.name);
  ref.put(imageFile)
    .then(snapshot => snapshot.ref.getDownloadURL())
    .then(url => db.collection("products").add({
      name, description: desc, price: parseFloat(price), category, imageUrl: url,
      userId: user.uid, userEmail: user.email, createdAt: firebase.firestore.FieldValue.serverTimestamp()
    }))
    .then(() => { showMessage("product-message", "✅ Product added successfully!"); clearProductForm(); })
    .catch(error => showMessage("product-message", "❌ Error: " + error.message));
}

function clearProductForm() {
  ["product-name","product-desc","product-price","product-category","product-image"].forEach(id => document.getElementById(id).value = "");
  document.getElementById("add-product-form").style.display = "none";
}

function renderProducts(products) {
  const list = document.getElementById("product-list");
  if (!list) return;
  if (products.length === 0) { list.innerHTML = `<p class="empty-message">No products yet. Click "Add New Product" to get started!</p>`; return; }
  list.innerHTML = "";
  products.forEach(p => {
    const card = document.createElement("div");
    card.className = "product-card";
    card.innerHTML = `
      <img src="${p.imageUrl}" class="product-image" onerror="this.src='https://via.placeholder.com/280x220?text=Product'" />
      <div class="product-info">
        <div class="product-name">${p.name}</div>
        <div class="product-price">₹${p.price}</div>
        <div class="product-actions" style="margin:10px 0;">
          <button class="btn btn-add-cart" onclick="addToCart('${p.id}')">Add to Cart</button>
          ${p.userId === currentUser.uid ? `<button class="btn btn-delete" onclick="deleteProduct('${p.id}')">Delete</button>` : ""}
        </div>
        <div class="product-category">${p.category}</div>
      </div>`;
    list.appendChild(card);
  });
}

let cart = [];
function addToCart(productId) {
  const product = allProducts.find(p => p.id === productId);
  if (product) { cart.push(product); alert("Added to cart!"); showSection('cart'); }
}

function renderCart() {
  const cartList = document.getElementById('cart-list');
  if (!cartList) return;
  if (cart.length === 0) { cartList.innerHTML = "<p>Your cart is empty.</p>"; return; }
  cartList.innerHTML = "";
  cart.forEach(product => {
    const card = document.createElement("div");
    card.className = "product-card";
    card.innerHTML = `
      <img src="${product.imageUrl}" class="product-image" onerror="this.src='https://via.placeholder.com/280x220?text=Product'"/>
      <div class="product-info">
        <div class="product-name">${product.name}</div>
        <div class="product-price">₹${product.price}</div>
      </div>`;
    cartList.appendChild(card);
  });
}

function updateUserStats() {
  const statElement = document.getElementById("stat-products");
  if (statElement) statElement.textContent = allProducts.length;
}

function deleteProduct(productId) {
  if (!confirm("Are you sure you want to delete this product?")) return;
  const product = allProducts.find(p => p.id === productId);
  if (!product || product.userId !== currentUser.uid) { alert("You don't have permission to delete this product."); return; }
  db.collection('products').doc(productId).delete()
    .then(() => alert("Product deleted successfully."))
    .catch(error => alert("Error deleting product: " + error.message));
}

// ===== AI STORY GENERATOR (now using real Groq LLM via Cloud Function) =====
const functionsInstance = firebase.functions();
const generateStoryCallable = functionsInstance.httpsCallable("generateStory");

async function generateStory() {
  const craft = document.getElementById("craft-type").value.trim();
  const region = document.getElementById("region").value.trim();
  const productName = document.getElementById("product-name-ai").value.trim();

  if (!craft || !region || !productName) {
    document.getElementById("story-result").innerHTML = "<p style='color: #c0152f;'>❌ Please fill all fields</p>";
    return;
  }

  document.getElementById("story-result").innerHTML = "<p>⏳ Generating your story...</p>";

  try {
    const result = await generateStoryCallable({ craftType: craft, region: region, productName: productName });
    document.getElementById("story-result").innerHTML = `
      <p><strong>✨ Your AI-Generated Story:</strong></p>
      <p style="white-space: pre-line; margin-top: 16px;">${result.data.story}</p>
      <p style="margin-top: 20px; font-size: 14px; color: #6C757D;"><em>💡 Tip: Copy this story and use it in your product listings!</em></p>`;
  } catch (error) {
    console.error("❌ Error generating story:", error);
    document.getElementById("story-result").innerHTML = "<p style='color: #c0152f;'>❌ Something went wrong. Please try again.</p>";
  }
}

function showMessage(elementId, message) {
  const el = document.getElementById(elementId);
  if (el) {
    el.textContent = message;
    el.style.display = 'block';
    setTimeout(() => { el.textContent = ""; el.style.display = 'none'; }, 5000);
  }
}

function initializeAllSections() { console.log("📋 Sections ready"); }

console.log("✅ App.js loaded successfully");