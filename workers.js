export default {
  async fetch(request, env) {
    // Handle CORS preflight
    if (request.method === "OPTIONS") {
      return new Response(null, {
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "POST, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type, Authorization",
        }
      });
    }

    if (request.method !== "POST") {
      return new Response("Only POST requests are allowed", { status: 405 });
    }

    try {
      // Check for authorization if needed
      // const authHeader = request.headers.get("Authorization");
      // if (authHeader !== `Bearer ${env.API_KEY}`) {
      //   return new Response("Unauthorized", { status: 401 });
      // }

      // We expect multipart/form-data
      const formData = await request.formData();
      const prompt = formData.get("prompt");
      const imageFile = formData.get("image");
      const maskFile = formData.get("mask");

      if (!prompt || !imageFile || !maskFile) {
        return new Response("Missing required fields: prompt, image, mask", { status: 400 });
      }

      // Convert files to ArrayBuffers
      const imageArrayBuffer = await imageFile.arrayBuffer();
      const maskArrayBuffer = await maskFile.arrayBuffer();

      // Convert ArrayBuffers to Uint8Arrays
      const imageUint8Array = new Uint8Array(imageArrayBuffer);
      const maskUint8Array = new Uint8Array(maskArrayBuffer);

      // Cloudflare AI model
      const model = "@cf/runwayml/stable-diffusion-v1-5-inpainting";

      // Call Cloudflare AI
      const input = {
        prompt: prompt,
        image: [...imageUint8Array],
        mask: [...maskUint8Array],
        guidance: 7.5,
        num_steps: 20,
        strength: 1.0
      };

      const response = await env.AI.run(model, input);

      // response is a Uint8Array containing the JPEG or PNG image data
      return new Response(response, {
        headers: {
          "Content-Type": "image/png",
          "Access-Control-Allow-Origin": "*"
        }
      });
    } catch (e) {
      return new Response(`Error: ${e.message}`, {
        status: 500,
        headers: {
          "Access-Control-Allow-Origin": "*"
        }
      });
    }
  }
};
