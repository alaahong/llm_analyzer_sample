package cn.ianzhang.authapi.model;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
@Disabled
class RoleTest {

    @Test
    void defaultConstructorCreatesRoleWithNullName() {
        Role role = new Role();
        assertNull(role.getName());
    }

    @Test
    void constructorWithNameSetsNameCorrectly() {
        Role role = new Role("admin");
        assertEquals("admin", role.getName());
    }

    @Test
    void setNameUpdatesName() {
        Role role = new Role();
        role.setName("user");
        assertEquals("user", role.getName());
    }

    @Test
    void setNameToNull() {
        Role role = new Role("admin");
        role.setName(null);
        assertNull(role.getName());
    }

    @Test
    void setNameToEmptyString() {
        Role role = new Role();
        role.setName("");
        assertEquals("", role.getName());
    }

    @Test
    void equalsReturnsTrueForSameObject() {
        Role role = new Role("admin");
        assertTrue(role.equals(role));
    }

    @Test
    void equalsReturnsFalseForNull() {
        Role role = new Role("admin");
        assertFalse(role.equals(null));
    }

    @Test
    void equalsReturnsFalseForDifferentClass() {
        Role role = new Role("admin");
        assertFalse(role.equals("string"));
    }

    @Test
    void equalsReturnsTrueForRolesWithSameName() {
        Role role1 = new Role("admin");
        Role role2 = new Role("admin");
        assertTrue(role1.equals(role2));
    }

    @Test
    void equalsReturnsFalseForRolesWithDifferentName() {
        Role role1 = new Role("admin");
        Role role2 = new Role("user");
        assertFalse(role1.equals(role2));
    }

    @Test
    void equalsReturnsFalseForOneNullName() {
        Role role1 = new Role("admin");
        Role role2 = new Role(null);
        assertFalse(role1.equals(role2));
    }

    @Test
    void equalsReturnsTrueForBothNullName() {
        Role role1 = new Role(null);
        Role role2 = new Role(null);
        assertTrue(role1.equals(role2));
    }

    @Test
    void hashCodeIsConsistentForSameName() {
        Role role1 = new Role("admin");
        Role role2 = new Role("admin");
        assertEquals(role1.hashCode(), role2.hashCode());
    }

    @Test
    void hashCodeDiffersForDifferentName() {
        Role role1 = new Role("admin");
        Role role2 = new Role("user");
        assertNotEquals(role1.hashCode(), role2.hashCode());
    }

    @Test
    void hashCodeIsSameForNullName() {
        Role role1 = new Role(null);
        Role role2 = new Role(null);
        assertEquals(role1.hashCode(), role2.hashCode());
    }

    @Test
    void toStringIncludesName() {
        Role role = new Role("admin");
        String expected = "Role{name='admin'}";
        assertEquals(expected, role.toString());
    }

    @Test
    void toStringHandlesNullName() {
        Role role = new Role(null);
        String expected = "Role{name='null'}";
        assertEquals(expected, role.toString());
    }

    @Test
    void toStringHandlesEmptyName() {
        Role role = new Role("");
        String expected = "Role{name=''}";
        assertEquals(expected, role.toString());
    }
}
