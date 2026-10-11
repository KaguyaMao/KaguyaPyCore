package cn.solarmoon.spark_core.physics;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.shapes.MeshCollisionShape;
import com.jme3.bullet.collision.shapes.infos.IndexedMesh;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.bullet.util.NativeLibrary;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativePhysicsLoaderTest {
    @Test
    void selectsOnlySupportedX8664Platforms() {
        assertEquals("natives/linux/x86_64/libbulletjme.so",
                NativePhysicsLoader.detectPlatform("Linux", "amd64").resourcePath());
        assertEquals("natives/windows/x86_64/bulletjme.dll",
                NativePhysicsLoader.detectPlatform("Windows 11", "x86_64").resourcePath());
        assertThrows(IllegalStateException.class,
                () -> NativePhysicsLoader.detectPlatform("Linux", "aarch64"));
        assertThrows(IllegalStateException.class,
                () -> NativePhysicsLoader.detectPlatform("Mac OS X", "x86_64"));
    }

    @Test
    void loadsBundledNativeAndSimulatesRigidBody() {
        NativePhysicsLoader.load();
        assertEquals(NativePhysicsLoader.VERSION, NativeLibrary.versionNumber());
        assertTrue(NativeLibrary.isThreadSafe());

        PhysicsSpace space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        try {
            space.setGravity(new Vector3f(0f, -9.81f, 0f));
            PhysicsRigidBody floor = new PhysicsRigidBody(new BoxCollisionShape(4f, .5f, 4f), 0f);
            floor.setPhysicsLocation(new Vector3f(0f, -.5f, 0f));
            floor.setCollisionGroup(1);
            floor.setCollideWithGroups(0);

            PhysicsRigidBody falling = new PhysicsRigidBody(new BoxCollisionShape(.5f), 1f);
            falling.setPhysicsLocation(new Vector3f(0f, 3f, 0f));
            falling.setCollisionGroup(2);
            falling.setCollideWithGroups(1);

            space.addCollisionObject(floor);
            space.addCollisionObject(falling);
            for (int step = 0; step < 240; step++) space.update(1f / 60f, 0);

            float finalY = falling.getPhysicsLocation(null).y;
            assertTrue(finalY > .35f && finalY < .8f, "body should settle on terrain, y=" + finalY);
        } finally {
            space.destroy();
        }
    }

    @Test
    void staticTriangleMeshSupportsDynamicBody() {
        NativePhysicsLoader.load();
        PhysicsSpace space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        try {
            space.setGravity(new Vector3f(0f, -9.81f, 0f));
            var vertices = new Vector3f[] {
                    new Vector3f(-4f, 0f, -4f), new Vector3f(-4f, 0f, 4f),
                    new Vector3f(4f, 0f, 4f), new Vector3f(4f, 0f, -4f)
            };
            // Downward winding verifies that Bullet treats a static terrain
            // mesh as two-sided for collision, independent of render winding.
            var mesh = new IndexedMesh(vertices, new int[] {0, 2, 1, 0, 3, 2});
            PhysicsRigidBody floor = new PhysicsRigidBody(new MeshCollisionShape(true, mesh), 0f);
            floor.setCollisionGroup(1);
            floor.setCollideWithGroups(0);

            PhysicsRigidBody falling = new PhysicsRigidBody(new BoxCollisionShape(.5f), 1f);
            falling.setPhysicsLocation(new Vector3f(0f, 3f, 0f));
            falling.setCollisionGroup(2);
            falling.setCollideWithGroups(1);

            space.addCollisionObject(floor);
            space.addCollisionObject(falling);
            for (int step = 0; step < 240; step++) space.update(1f / 60f, 0);

            float finalY = falling.getPhysicsLocation(null).y;
            assertTrue(finalY > .35f && finalY < .8f, "body should settle on triangle terrain, y=" + finalY);
        } finally {
            space.destroy();
        }
    }
}
