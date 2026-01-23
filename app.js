console.log("🚀 Kalasetu AI Assistant with Cart Persistence Loading...");

// ============================================
// FIREBASE CONFIG
// ============================================

const firebaseConfig = {
  apiKey: "process.env.FIREBASE_API_KEY",
  authDomain: "process.env.FIREBASE_AUTH_DOMAIN",
  projectId: " ",
  storageBucket: " ",
  messagingSenderId: " ",
  appId: " "
};

firebase.initializeApp(firebaseConfig);
const auth = firebase.auth();
const db = firebase.firestore();
const storage = firebase.storage();

console.log("✅ Firebase Initialized");

let currentUser = null;
let allProducts = [];
let cart = []; // In-memory cart
const CART_COLLECTION = "carts";

// ============================================
// CART PERSISTENCE - FIRESTORE
// ============================================

// Load cart from Firestore when user logs in
async function loadCartFromFirestore(userId) {
  try {
    const cartQuery = await db.collection(CART_COLLECTION)
      .where("userId", "==", userId)
      .get();
    
    if (!cartQuery.empty) {
      const cartData = cartQuery.docs[0].data();
      cart = cartData.items || [];
      console.log("✅ Cart loaded from Firestore:", cart);
    } else {
      cart = [];
      console.log("✅ New cart initialized");
    }
    
    renderCart();
  } catch (error) {
    console.error("❌ Error loading cart:", error);
    cart = [];
  }
}

// Save cart to Firestore (called after every cart action)
async function saveCartToFirestore(userId) {
  try {
    const cartRef = db.collection(CART_COLLECTION).doc(userId);
    await cartRef.set({
      userId: userId,
      items: cart,
      updatedAt: new Date(),
      totalPrice: calculateTotalPrice()
    });
    console.log("✅ Cart saved to Firestore");
  } catch (error) {
    console.error("❌ Error saving cart:", error);
  }
}

// Add product to cart
function addToCart(product) {
  if (!currentUser) {
    alert("❌ Please login first to add to cart");
    return;
  }

  // Check if product already in cart
  const existingItem = cart.find(item => item.id === product.id);
  
  if (existingItem) {
    existingItem.quantity += 1;
  } else {
    cart.push({
      id: product.id,
      name: product.name,
      price: product.price,
      imageUrl: product.imageUrl,
      quantity: 1
    });
  }

  saveCartToFirestore(currentUser.uid);
  renderCart();
  alert("✅ Product added to cart!");
}

// Remove product from cart
function removeFromCart(productId) {
  if (!currentUser) return;

  cart = cart.filter(item => item.id !== productId);
  saveCartToFirestore(currentUser.uid);
  renderCart();
}

// Update cart quantity
function updateCartQuantity(productId, newQuantity) {
  if (!currentUser) return;

  if (newQuantity <= 0) {
    removeFromCart(productId);
    return;
  }

  const item = cart.find(item => item.id === productId);
  if (item) {
    item.quantity = parseInt(newQuantity) || 1;
    saveCartToFirestore(currentUser.uid);
    renderCart();
  }
}

// Calculate total price
function calculateTotalPrice() {
  return cart.reduce((total, item) => total + (item.price * item.quantity), 0).toFixed(2);
}

// Render cart items
function renderCart() {
  const cartList = document.getElementById("cart-list");
  const cartTotal = document.getElementById("cart-total");
  
  if (!cartList || !cartTotal) return;

  if (cart.length === 0) {
    cartList.innerHTML = "<p style='text-align:center; color: #999;'>Your cart is empty.</p>";
    cartTotal.textContent = "₹0";
    return;
  }

  let cartHTML = "";
  
  cart.forEach(item => {
    cartHTML += `
      <div class="cart-item" style="display: flex; gap: 16px; padding: 16px; border: 1px solid #ddd; border-radius: 8px; margin-bottom: 12px; align-items: center;">
        <img src="${item.imageUrl}" alt="${item.name}" style="width: 80px; height: 80px; border-radius: 6px; object-fit: cover;">
        
        <div style="flex: 1;">
          <h4 style="margin: 0 0 8px 0;">${item.name}</h4>
          <p style="margin: 0 0 8px 0; color: #666;">₹${item.price} each</p>
          
          <div style="display: flex; gap: 8px; align-items: center;">
            <button onclick="updateCartQuantity('${item.id}', ${item.quantity - 1})" style="padding: 4px 8px; border: 1px solid #ccc; border-radius: 4px; cursor: pointer;">−</button>
            <input type="number" value="${item.quantity}" min="1" onchange="updateCartQuantity('${item.id}', this.value)" style="width: 50px; padding: 4px; border: 1px solid #ccc; border-radius: 4px; text-align: center;">
            <button onclick="updateCartQuantity('${item.id}', ${item.quantity + 1})" style="padding: 4px 8px; border: 1px solid #ccc; border-radius: 4px; cursor: pointer;">+</button>
          </div>
        </div>
        
        <div style="text-align: right;">
          <p style="margin: 0 0 8px 0; font-weight: 600;">₹${(item.price * item.quantity).toFixed(2)}</p>
          <button onclick="removeFromCart('${item.id}')" style="padding: 6px 12px; background: #ff4444; color: white; border: none; border-radius: 4px; cursor: pointer;">🗑️ Remove</button>
        </div>
      </div>
    `;
  });

  cartList.innerHTML = cartHTML;
  cartTotal.textContent = `₹${calculateTotalPrice()}`;
}

// Clear cart after purchase
async function clearCart() {
  if (!currentUser) return;

  cart = [];
  await saveCartToFirestore(currentUser.uid);
  renderCart();
}

// ============================================
// AUTHENTICATION
// ============================================

function signup() {
  const email = document.getElementById("email").value.trim();
  const password = document.getElementById("password").value.trim();

  if (!email || !password) {
    showMessage("auth-message", "❌ Please enter email and password");
    return;
  }

  if (password.length < 6) {
    showMessage("auth-message", "❌ Password must be 6+ characters");
    return;
  }

  showMessage("auth-message", "⏳ Creating account...");
  auth.createUserWithEmailAndPassword(email, password)
    .then(userCredential => {
      console.log("✅ Signup successful");
      showMessage("auth-message", "✅ Account created! Logging you in...");
      
      const user = userCredential.user;
      db.collection("users").doc(user.uid).set({
        uid: user.uid,
        email: user.email,
        displayName: user.email.split('@')[0],
        name: "",
        craftType: "",
        location: "",
        experienceYears: 0,
        connectedUserIds: [],
        createdAt: firebase.firestore.FieldValue.serverTimestamp()
      });

      showDashboard(user);
    })
    .catch(error => {
      console.error("Signup error:", error);
      showMessage("auth-message", "❌ " + error.message);
    });
}

function login() {
  const email = document.getElementById("email").value.trim();
  const password = document.getElementById("password").value.trim();

  if (!email || !password) {
    showMessage("auth-message", "❌ Please enter email and password");
    return;
  }

  showMessage("auth-message", "⏳ Logging in...");
  auth.signInWithEmailAndPassword(email, password)
    .then(userCredential => {
      console.log("✅ Login successful");
      showMessage("auth-message", "✅ Welcome back!");
      showDashboard(userCredential.user);
    })
    .catch(error => {
      console.error("Login error:", error);
      showMessage("auth-message", "❌ " + error.message);
    });
}

function logout() {
  auth.signOut().then(() => {
    console.log("✅ Logout successful");
    currentUser = null;
    allProducts = [];
    cart = [];
    document.getElementById("login-section").style.display = "block";
    document.getElementById("navbar").style.display = "none";
    document.getElementById("user-profile").style.display = "none";
    document.querySelectorAll(".section-content").forEach(s => s.style.display = "none");
    showMessage("auth-message", "✅ Logged out successfully");
  });
}

function showDashboard(user) {
  currentUser = user;
  console.log("📱 Setting up dashboard for user:", user.uid);

  document.getElementById("login-section").style.display = "none";
  document.getElementById("navbar").style.display = "block";
  document.getElementById("user-profile").style.display = "flex";

  const firstName = user.email.split('@')[0].split('.')[0];
  const capitalizedName = firstName.charAt(0).toUpperCase() + firstName.slice(1);
  document.getElementById("user-greeting").textContent = `Namaste, ${capitalizedName} ji!`;

  const dashboardGreeting = document.getElementById("dashboard-greeting");
  if (dashboardGreeting) {
    dashboardGreeting.textContent = `Welcome back, ${capitalizedName}! Here's your artisan overview.`;
  }

  const aiGreeting = document.getElementById("ai-greeting");
  if (aiGreeting) {
    const hour = new Date().getHours();
    const timeGreeting = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
    aiGreeting.textContent = `${timeGreeting}, ${capitalizedName}! I can help you create compelling stories for your beautiful crafts. What would you like to work on today?`;
  }

  // LOAD CART FROM FIRESTORE
  loadCartFromFirestore(user.uid);

  showSection('dashboard');
  setupProductsListener();
  initializeAllSections();
}

// ============================================
// PRODUCTS MANAGEMENT
// ============================================

function setupProductsListener() {
  if (!currentUser) {
    console.warn("❌ No user for products listener");
    return;
  }

  console.log("📡 Setting up products listener for user:", currentUser.uid);

  db.collection("products")
    .orderBy("createdAt", "desc")
    .onSnapshot(snapshot => {
      console.log("📦 Products updated, count:", snapshot.size);
      
      allProducts = [];
      snapshot.forEach(doc => {
        allProducts.push({ id: doc.id, ...doc.data() });
      });

      refreshProductsList();
      updateUserStats();
    }, error => {
      console.error("❌ Error in products listener:", error);
    });
}

function refreshProductsList() {
  console.log("🔄 Refreshing products list, count:", allProducts.length);
  renderProducts(allProducts);
}

function showAddProduct() {
  const form = document.getElementById("add-product-form");
  if (form) {
    form.style.display = form.style.display === "none" ? "block" : "none";
  }
}

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

  console.log("📝 Adding product:", { name, desc, price, category, imageFile: !!imageFile });

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
    .then(snapshot => {
      console.log("✅ Image uploaded successfully");
      showMessage("product-message", "⏳ Saving product details...");
      return snapshot.ref.getDownloadURL();
    })
    .then(url => {
      console.log("🔗 Got download URL:", url);
      return db.collection("products").add({
        name: name,
        description: desc,
        price: parseFloat(price),
        category: category,
        imageUrl: url,
        userId: user.uid,
        userEmail: user.email,
        createdAt: firebase.firestore.FieldValue.serverTimestamp()
      });
    })
    .then((docRef) => {
      console.log("✅ Product saved with ID:", docRef.id);
      showMessage("product-message", "✅ Product added successfully!");
      clearProductForm();
    })
    .catch(error => {
      console.error("❌ Error adding product:", error);
      showMessage("product-message", "❌ Error: " + error.message);
    });
}

function clearProductForm() {
  document.getElementById("product-name").value = "";
  document.getElementById("product-desc").value = "";
  document.getElementById("product-price").value = "";
  document.getElementById("product-category").value = "";
  document.getElementById("product-image").value = "";
  document.getElementById("add-product-form").style.display = "none";
}

function renderProducts(products) {
  const list = document.getElementById("product-list");
  if (!list) return;

  console.log("🎨 Rendering", products.length, "products");

  if (products.length === 0) {
    list.innerHTML = `<p style='text-align:center;'>No products yet. Add your first product!</p>`;
    return;
  }

  list.innerHTML = "";
  products.forEach(p => {
    const card = document.createElement("div");
    card.className = "product-card";
    card.innerHTML = `
      <img src="${p.imageUrl}" alt="${p.name}" style="width: 100%; height: 200px; object-fit: cover; border-radius: 8px;">
      <h3>${p.name}</h3>
      <p>${p.description}</p>
      <p style="font-weight: 600; color: #FF8C00;">₹${p.price}</p>
      <p style="color: #666; font-size: 12px;">${p.category}</p>
      <button onclick="addToCart({id: '${p.id}', name: '${p.name}', price: ${p.price}, imageUrl: '${p.imageUrl}'})" 
              style="width: 100%; padding: 10px; background: #32B8C6; color: white; border: none; border-radius: 6px; cursor: pointer; font-weight: 600;">
        🛒 Add to Cart
      </button>
    `;
    list.appendChild(card);
  });
}

// ============================================
// NAVIGATION
// ============================================

function showSection(sectionId) {
  console.log("🔄 Showing section:", sectionId);

  document.querySelectorAll(".section-content").forEach(section => {
    section.style.display = "none";
  });

  const targetSection = document.getElementById(sectionId + "-section");
  if (targetSection) {
    targetSection.style.display = "block";

    if (sectionId === 'cart') {
      renderCart();
    } else if (sectionId === 'products') {
      refreshProductsList();
    }
  }

  window.scrollTo({ top: 0, behavior: 'smooth' });
}

// ============================================
// HELPERS
// ============================================

function showMessage(elementId, message) {
  const el = document.getElementById(elementId);
  if (el) {
    el.textContent = message;
    el.style.display = 'block';
    setTimeout(() => {
      el.textContent = "";
      el.style.display = 'none';
    }, 5000);
  }
}

function updateUserStats() {
  if (!currentUser) return;

  const productsCount = allProducts.filter(p => p.userId === currentUser.uid).length;
  const productsElement = document.getElementById("products-count");
  if (productsElement) {
    productsElement.textContent = productsCount;
  }
}

function initializeAllSections() {
  console.log("📋 Initializing all sections");
}

// Monitor auth state
auth.onAuthStateChanged(user => {
  if (user) {
    showDashboard(user);
  }
});

console.log("✅ App.js loaded successfully");
