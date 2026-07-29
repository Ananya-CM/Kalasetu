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
const CART_COLLECTION = "carts";

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

function checkAndPromptProfileSetup(user) {
  db.collection('users').doc(user.uid).get().then(doc => {
    const data = doc.data() || {};
    if (!data.name || !data.craftType || !data.location || !data.experienceYears) {
      document.getElementById("profile-setup-modal").style.display = "block";
      document.getElementById("setup-name").value = data.name || "";
      document.getElementById("setup-location").value = data.location || "";
      document.getElementById("setup-craft").value = data.craftType || "";
      document.getElementById("setup-exp").value = data.experienceYears || "";
    }
  });
}

document.addEventListener('DOMContentLoaded', function() {
  const form = document.getElementById("profile-setup-form");
  if (form) {
    form.onsubmit = function(event) {
      event.preventDefault();
      const user = auth.currentUser;
      if (!user) return;

      const name = document.getElementById("setup-name").value.trim();
      const location = document.getElementById("setup-location").value.trim();
      const craftType = document.getElementById("setup-craft").value.trim();
      const experienceYears = parseInt(document.getElementById("setup-exp").value, 10) || 0;

      db.collection("users").doc(user.uid).update({
        name: name,
        location: location,
        craftType: craftType,
        experienceYears: experienceYears
      }).then(() => {
        document.getElementById("profile-setup-modal").style.display = "none";
        alert("Profile updated!");
      });
    };
  }
});

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
  loadCartFromFirestore(user.uid);
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
}

auth.onAuthStateChanged(user => {
  if (user) {
    showDashboard(user);
    checkAndPromptProfileSetup(user);
  }
});

function showSection(sectionId) {
  document.querySelectorAll(".section-content").forEach(section => section.style.display = "none");
  const targetSection = document.getElementById(sectionId + "-section");
  if (targetSection) {
    targetSection.style.display = "block";
    if (sectionId === 'products') refreshProductsList();
    else if (sectionId === 'cart') renderCart();
    else if (sectionId === 'community') loadCommunityProfiles();
    else if (sectionId === 'market-intel') initializeMarketIntel();
    else if (sectionId === 'marketing') initializeMarketing();
    else if (sectionId === 'learning') initializeLearning();
    else if (sectionId === 'analytics') initializeAnalytics();
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
    if (document.getElementById('market-intel-section').style.display === 'block') initializeMarketIntel();
    if (document.getElementById('analytics-section').style.display === 'block') initializeAnalytics();
  }, error => console.error("❌ Error in products listener:", error));
}

function refreshProductsList() { renderProducts(allProducts); }

function showAddProduct() {
  const form = document.getElementById("add-product-form");
  if (form) form.style.display = form.style.display === "none" ? "block" : "none";
}

const addProductCallable = firebase.functions().httpsCallable("addProduct");
const deleteProductCallable = firebase.functions().httpsCallable("deleteProduct");

function addProduct() {
  const user = auth.currentUser;
  if (!user) {
    showMessage("product-message", "❌ Please login first");
    return;
  }

  const name = document.getElementById("product-name").value.trim();
  const desc = document.getElementById("product-desc").value.trim();
  const price = document.getElementById("product-price").value;
  const category = document.getElementById("product-category").value;
  const imageFile = document.getElementById("product-image").files[0];

  if (!name || !desc || !price || !category || !imageFile) {
    showMessage("product-message", "❌ Please fill all fields and select an image");
    return;
  }
  if (isNaN(price) || price <= 0) {
    showMessage("product-message", "❌ Please enter a valid price");
    return;
  }

  showMessage("product-message", "⏳ Uploading product image...");
  const ref = storage.ref("products/" + user.uid + "/" + Date.now() + "-" + imageFile.name);

  ref.put(imageFile)
    .then(snapshot => snapshot.ref.getDownloadURL())
    .then(imageUrl => {
      showMessage("product-message", "⏳ Saving product...");
      return addProductCallable({
        name,
        description: desc,
        price: parseFloat(price),
        imageUrl,
        category
      });
    })
    .then(() => {
      showMessage("product-message", "✅ Product added successfully!");
      clearProductForm();
    })
    .catch(error => {
      console.error("❌ Error adding product:", error);
      showMessage("product-message", "❌ Error: " + error.message);
    });
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

async function loadCartFromFirestore(userId) {
  try {
    const cartDoc = await db.collection(CART_COLLECTION).doc(userId).get();
    cart = cartDoc.exists ? (cartDoc.data().items || []) : [];
    renderCart();
  } catch (error) {
    console.error("❌ Error loading cart:", error);
    cart = [];
  }
}

async function saveCartToFirestore() {
  if (!currentUser) return;
  try {
    await db.collection(CART_COLLECTION).doc(currentUser.uid).set({
      userId: currentUser.uid,
      items: cart,
      updatedAt: firebase.firestore.FieldValue.serverTimestamp()
    });
  } catch (error) {
    console.error("❌ Error saving cart:", error);
  }
}

function addToCart(productId) {
  const product = allProducts.find(p => p.id === productId);
  if (!product) return;

  const existingItem = cart.find(item => item.id === productId);
  if (existingItem) {
    existingItem.quantity = (existingItem.quantity || 1) + 1;
  } else {
    cart.push({ ...product, quantity: 1 });
  }

  saveCartToFirestore();
  alert("Added to cart!");
  showSection('cart');
}

function updateCartQuantity(productId, newQuantity) {
  newQuantity = parseInt(newQuantity, 10);
  if (isNaN(newQuantity) || newQuantity < 1) {
    removeFromCart(productId);
    return;
  }
  const item = cart.find(i => i.id === productId);
  if (item) {
    item.quantity = newQuantity;
    saveCartToFirestore();
    renderCart();
  }
}

function removeFromCart(productId) {
  cart = cart.filter(item => item.id !== productId);
  saveCartToFirestore();
  renderCart();
}

function calculateCartTotal() {
  return cart.reduce((total, item) => total + (item.price * (item.quantity || 1)), 0);
}

function renderCart() {
  const cartList = document.getElementById('cart-list');
  if (!cartList) return;

  if (cart.length === 0) {
    cartList.innerHTML = "<p>Your cart is empty.</p>";
    const totalEl = document.getElementById('cart-total');
    if (totalEl) totalEl.textContent = "₹0";
    return;
  }

  cartList.innerHTML = "";
  cart.forEach(product => {
    const qty = product.quantity || 1;
    const card = document.createElement("div");
    card.className = "product-card";
    card.innerHTML = `
      <img src="${product.imageUrl}" class="product-image" onerror="this.src='https://via.placeholder.com/280x220?text=Product'"/>
      <div class="product-info">
        <div class="product-name">${product.name}</div>
        <div class="product-price">₹${product.price} each</div>
        <div style="display:flex; align-items:center; gap:8px; margin:10px 0;">
          <button onclick="updateCartQuantity('${product.id}', ${qty - 1})" style="padding:4px 10px;">−</button>
          <input type="number" value="${qty}" min="1" style="width:50px; text-align:center;"
            onchange="updateCartQuantity('${product.id}', this.value)">
          <button onclick="updateCartQuantity('${product.id}', ${qty + 1})" style="padding:4px 10px;">+</button>
        </div>
        <div style="font-weight:600;">Subtotal: ₹${(product.price * qty).toFixed(2)}</div>
        <button class="btn btn-delete" onclick="removeFromCart('${product.id}')" style="margin-top:8px;">Remove</button>
      </div>
    `;
    cartList.appendChild(card);
  });

  const totalEl = document.getElementById('cart-total');
  if (totalEl) totalEl.textContent = `₹${calculateCartTotal().toFixed(2)}`;
}

function updateUserStats() {
  const statElement = document.getElementById("stat-products");
  if (statElement) statElement.textContent = allProducts.length;
}

function deleteProduct(productId) {
  if (!confirm("Are you sure you want to delete this product?")) return;

  deleteProductCallable({ productId })
    .then(() => alert("Product deleted successfully."))
    .catch(error => {
      console.error("Error deleting product:", error);
      alert("Error deleting product: " + (error.message || "Permission denied or product not found."));
    });
}

function loadCommunityProfiles() {
  db.collection("users").doc(currentUser.uid).get().then(currentDoc => {
    const connectedUserIds = (currentDoc.exists && currentDoc.data().connectedUserIds) || [];

    db.collection('users').get().then(snapshot => {
      let users = [];
      snapshot.forEach(doc => {
        if (doc.id !== currentUser.uid) {
          users.push({ uid: doc.id, ...doc.data() });
        }
      });
      renderCommunityProfiles(users, connectedUserIds);
    });
  });
}

function renderCommunityProfiles(users, connectedUserIds) {
  const grid = document.getElementById("community-grid");
  if (!grid) return;

  let connectedHtml = "";
  let notConnectedHtml = "";

  users.forEach(user => {
    if (!user.name) return; // skip incomplete profiles
    const isConnected = connectedUserIds.includes(user.uid);
    const cardHtml = `
      <div style="display:flex; align-items:center; padding:14px; border:1px solid #ddd; border-radius:8px; margin-bottom:10px;">
        <div style="flex:1;">
          <strong>${user.name}</strong>
          <p style="font-size:13px;color:#6C757D;margin:2px 0;">${user.craftType || "Craft not set"} • ${user.location || "Location not set"}</p>
          <p style="font-size:12px;margin:0;">${user.experienceYears || 0} years experience</p>
        </div>
        <div>
          ${isConnected
            ? `<button class="btn btn-sm btn-danger" onclick="disconnectUser('${user.uid}')">Disconnect</button>`
            : `<button class="btn btn-sm btn-secondary" onclick="connectUser('${user.uid}')">Connect</button>`}
        </div>
      </div>
    `;
    if (isConnected) connectedHtml += cardHtml;
    else notConnectedHtml += cardHtml;
  });

  grid.innerHTML = `
    <h3>Connected Artisans</h3>
    ${connectedHtml || "<p>No connections yet.</p>"}
    <h3 style="margin-top:24px;">Other Artisans</h3>
    ${notConnectedHtml || "<p>No other artisans yet.</p>"}
  `;
}

function connectUser(otherUid) {
  db.collection("users").doc(currentUser.uid).update({
    connectedUserIds: firebase.firestore.FieldValue.arrayUnion(otherUid)
  }).then(() => loadCommunityProfiles());
}

function disconnectUser(otherUid) {
  db.collection("users").doc(currentUser.uid).update({
    connectedUserIds: firebase.firestore.FieldValue.arrayRemove(otherUid)
  }).then(() => loadCommunityProfiles());
}

function initializeMarketIntel() {
  const section = document.getElementById('market-intel-section');
  if (!section) return;

  const categoryCounts = {};
  const categoryTotalPrice = {};
  allProducts.forEach(p => {
    const cat = p.category || "Uncategorized";
    categoryCounts[cat] = (categoryCounts[cat] || 0) + 1;
    categoryTotalPrice[cat] = (categoryTotalPrice[cat] || 0) + (p.price || 0);
  });

  const sortedCategories = Object.keys(categoryCounts).sort((a, b) => categoryCounts[b] - categoryCounts[a]);

  let trendsHtml = "";
  if (sortedCategories.length === 0) {
    trendsHtml = "<p>No products listed yet across the platform.</p>";
  } else {
    sortedCategories.slice(0, 5).forEach(cat => {
      const count = categoryCounts[cat];
      const avgPrice = (categoryTotalPrice[cat] / count).toFixed(0);
      trendsHtml += `
        <div class="trend-item">
          <div class="trend-name">${cat}</div>
          <div class="trend-badge high">${count} listing${count > 1 ? 's' : ''}</div>
          <p class="trend-info">Average price: ₹${avgPrice}</p>
        </div>`;
    });
  }

  const myProducts = allProducts.filter(p => currentUser && p.userId === currentUser.uid);
  const myAvg = myProducts.length ? myProducts.reduce((s, p) => s + (p.price || 0), 0) / myProducts.length : 0;
  const overallAvg = allProducts.length ? allProducts.reduce((s, p) => s + (p.price || 0), 0) / allProducts.length : 0;

  section.innerHTML = `
    <div class="container">
      <h1>📊 Market Intelligence</h1>
      <p class="section-subtitle">Live data from ${allProducts.length} listings on Kalasetu</p>
      <div class="content-grid">
        <div class="content-card">
          <h3>🔥 Top Categories Right Now</h3>
          ${trendsHtml}
        </div>
        <div class="content-card">
          <h3>💰 Price Comparison</h3>
          <p><strong>Platform Average:</strong> ₹${overallAvg.toFixed(0)}</p>
          <p><strong>Your Average:</strong> ₹${myAvg.toFixed(0)}</p>
          ${myProducts.length === 0
            ? "<p>Add products to see your comparison.</p>"
            : myAvg < overallAvg
              ? `<p style="color:#28a745;margin-top:12px;">✓ Your pricing is below platform average.</p>`
              : `<p style="color:#e67e22;margin-top:12px;">⚠ Your pricing is above platform average.</p>`}
        </div>
      </div>
    </div>
  `;
}

function initializeMarketing() {
  const section = document.getElementById('marketing-section');
  if (!section) return;
  section.innerHTML = `
    <div class="container">
      <h1>📱 Digital Marketing Toolkit</h1>
      <p class="section-subtitle">AI-powered captions, generated live via Groq</p>
      <div class="content-card">
        <h3>📸 Social Media Caption Generator</h3>
        <textarea id="caption-input" class="form-control" placeholder="Describe your product..." style="margin:12px 0; width:100%; min-height:80px;"></textarea>
        <button class="btn btn-primary" onclick="generateMarketingCaption()">Generate Caption</button>
        <div id="caption-result" style="margin-top:16px;"></div>
      </div>
    </div>
  `;
}

const generateCaptionCallable = firebase.functions().httpsCallable("generateCaption");

async function generateMarketingCaption() {
  const input = document.getElementById("caption-input").value.trim();
  const resultDiv = document.getElementById("caption-result");
  if (!input) {
    resultDiv.innerHTML = "<p style='color:#c0152f;'>Please describe your product first.</p>";
    return;
  }
  resultDiv.innerHTML = "<p>⏳ Generating caption...</p>";
  try {
    const result = await generateCaptionCallable({ productDescription: input });
    resultDiv.innerHTML = `<div style="background:#f0f9fa;padding:16px;border-radius:8px;white-space:pre-line;">${result.data.caption}</div>`;
  } catch (error) {
    console.error("Error generating caption:", error);
    resultDiv.innerHTML = "<p style='color:#c0152f;'>Something went wrong. Please try again.</p>";
  }
}

const searchYouTubeCallable = firebase.functions().httpsCallable("searchYouTubeVideos");

async function initializeLearning() {
  const section = document.getElementById('learning-section');
  if (!section) return;

  section.innerHTML = `
    <div class="container">
      <h1>📚 Learning & Development Hub</h1>
      <p class="section-subtitle">Real tutorials based on your craft</p>
      <div id="learning-videos">
        <p>⏳ Loading videos for your craft...</p>
      </div>
    </div>
  `;

  const craftType = (currentUser && await getUserCraftType()) || "handmade crafts";

  try {
    const result = await searchYouTubeCallable({ craftType });
    renderLearningVideos(result.data.videos, craftType);
  } catch (error) {
    console.error("Error loading videos:", error);
    document.getElementById('learning-videos').innerHTML =
      "<p style='color:#c0152f;'>Couldn't load videos right now. Please try again.</p>";
  }
}

async function getUserCraftType() {
  try {
    const doc = await db.collection("users").doc(currentUser.uid).get();
    return doc.exists ? doc.data().craftType : null;
  } catch {
    return null;
  }
}

function renderLearningVideos(videos, craftType) {
  const container = document.getElementById('learning-videos');
  if (!videos || videos.length === 0) {
    container.innerHTML = "<p>No videos found.</p>";
    return;
  }

  let html = `<p style="margin-bottom:16px;">Showing results for: <strong>${craftType}</strong></p>`;
  html += `<div class="content-grid">`;
  videos.forEach(video => {
    html += `
      <div class="content-card">
        <iframe width="100%" height="180" src="https://www.youtube.com/embed/${video.videoId}"
          frameborder="0" allowfullscreen style="border-radius:8px;"></iframe>
        <h4 style="margin-top:10px;">${video.title}</h4>
        <p style="font-size:13px;color:#6C757D;">${video.channelTitle}</p>
      </div>
    `;
  });
  html += `</div>`;
  container.innerHTML = html;
}

function initializeAnalytics() {
  const section = document.getElementById('analytics-section');
  if (!section) return;

  const myProducts = allProducts.filter(p => currentUser && p.userId === currentUser.uid);
  const totalValue = myProducts.reduce((sum, p) => sum + (p.price || 0), 0);

  const categoryBreakdown = {};
  myProducts.forEach(p => {
    const cat = p.category || "Uncategorized";
    categoryBreakdown[cat] = (categoryBreakdown[cat] || 0) + 1;
  });
  const topCategory = Object.keys(categoryBreakdown).sort((a, b) => categoryBreakdown[b] - categoryBreakdown[a])[0];

  section.innerHTML = `
    <div class="container">
      <h1>📈 Sales Analytics</h1>
      <p class="section-subtitle">Live snapshot of your listings</p>
      <div class="content-grid">
        <div class="content-card">
          <h3>📦 Your Listings</h3>
          <p style="font-size:32px;font-weight:700;color:#32b8c6;">${myProducts.length}</p>
          <p>Active products listed</p>
        </div>
        <div class="content-card">
          <h3>💰 Listed Value</h3>
          <p style="font-size:32px;font-weight:700;color:#32b8c6;">₹${totalValue.toLocaleString()}</p>
          <p>Combined value of your listings</p>
        </div>
      </div>
      <div class="content-card" style="margin-top:20px;">
        <h3>🏷️ Your Top Category</h3>
        <p>${topCategory ? `${topCategory} (${categoryBreakdown[topCategory]} listing${categoryBreakdown[topCategory] > 1 ? 's' : ''})` : "No products listed yet."}</p>
      </div>
    </div>
  `;
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

console.log("✅ App.js loaded successfully");